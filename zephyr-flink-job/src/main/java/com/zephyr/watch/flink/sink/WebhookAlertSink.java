package com.zephyr.watch.flink.sink;

import com.zephyr.watch.common.constants.StorageConfig;
import com.zephyr.watch.common.entity.AlertEvent;
import com.zephyr.watch.common.webhook.WebhookHttpTransport;
import com.zephyr.watch.common.webhook.WebhookSendLogSql;
import com.zephyr.watch.common.webhook.WebhookUrlSigner;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class WebhookAlertSink extends RichSinkFunction<AlertEvent> {

    private final String webhookUrl;
    private transient List<WebhookTarget> targets;
    private transient int connectTimeoutMs;
    private transient int readTimeoutMs;
    private transient int maxAttempts;

    public WebhookAlertSink() {
        this(StorageConfig.ALERT_WEBHOOK_URL);
    }

    public WebhookAlertSink(String webhookUrl) {
        this.webhookUrl = webhookUrl;
    }

    @Override
    public void open(Configuration parameters) {
        this.connectTimeoutMs = intEnv("ZEPHYR_WEBHOOK_CONNECT_TIMEOUT_MS", 5000);
        this.readTimeoutMs = intEnv("ZEPHYR_WEBHOOK_READ_TIMEOUT_MS", 5000);
        this.maxAttempts = Math.max(1, intEnv("ZEPHYR_WEBHOOK_MAX_ATTEMPTS", 2));
        this.targets = loadTargets();
    }

    @Override
    public void invoke(AlertEvent value, Context context) throws Exception {
        if (targets == null || targets.isEmpty()) {
            return;
        }

        for (WebhookTarget target : targets) {
            if (!isAllowedByRiskLevel(value.getRiskLevel(), target.minRiskLevel)) {
                continue;
            }
            byte[] payload = WebhookChannelBodies.toJsonUtf8(target.webhookType, value);
            String signedUrl = WebhookUrlSigner.appendSignQuery(target.webhookUrl, target.signSecret);
            try {
                postWithRetries(target, signedUrl, payload, value);
            } catch (Exception e) {
                System.err.println("ZEPHYR_WEBHOOK_FAIL|channel=" + target.webhookType + "|url="
                        + target.webhookUrl + "|" + e.getMessage());
            }
        }
    }

    private List<WebhookTarget> loadTargets() {
        List<WebhookTarget> loaded = new ArrayList<>();
        if (webhookUrl != null && !webhookUrl.trim().isEmpty()) {
            String envSecret = StorageConfig.ALERT_WEBHOOK_SIGN_SECRET;
            String envType = StorageConfig.ALERT_WEBHOOK_TYPE;
            String envMin = StorageConfig.ALERT_WEBHOOK_MIN_RISK_LEVEL;
            loaded.add(new WebhookTarget(null, webhookUrl.trim(), envMin, envType, envSecret));
        }

        try (Connection connection = DriverManager.getConnection(
            StorageConfig.MYSQL_URL,
            StorageConfig.MYSQL_USER,
            StorageConfig.MYSQL_PASSWORD
        );
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                 "SELECT webhook_id, webhook_url, min_risk_level, webhook_type, webhook_sign_secret "
                     + "FROM webhook_config WHERE enabled = 1"
             )) {
            while (rs.next()) {
                String url = rs.getString("webhook_url");
                if (url != null && !url.trim().isEmpty()) {
                    long wid = rs.getLong("webhook_id");
                    Long webhookId = rs.wasNull() ? null : wid;
                    String wtype = rs.getString("webhook_type");
                    String secret = rs.getString("webhook_sign_secret");
                    loaded.add(new WebhookTarget(
                        webhookId,
                        url.trim(),
                        rs.getString("min_risk_level"),
                        wtype,
                        secret
                    ));
                }
            }
        } catch (Exception e) {
            System.err.println("WebhookAlertSink disabled: failed to load webhook_config from MySQL: "
                    + e.getMessage());
        }
        return loaded;
    }

    private void postWithRetries(WebhookTarget target, String signedUrl, byte[] payload, AlertEvent value)
            throws Exception {
        Exception last = null;
        int lastHttp = 0;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                WebhookHttpTransport.Result r = WebhookHttpTransport.postJson(
                        signedUrl,
                        payload,
                        connectTimeoutMs,
                        readTimeoutMs
                );
                lastHttp = r.getHttpStatus();
                if (lastHttp < 400 && r.isVendorOk()) {
                    insertSendLog(target, value, "SUCCESS", lastHttp, attempt, null);
                    return;
                }
                last = new IllegalStateException(
                        "Webhook non-ok, status=" + lastHttp + " body=" + r.getResponseBody()
                );
            } catch (Exception e) {
                last = e;
            }
            if (attempt < maxAttempts) {
                Thread.sleep(200L * attempt);
            }
        }
        insertSendLog(target, value, "FAILED", lastHttp, maxAttempts,
                last != null ? truncate(last.getMessage(), 1000) : "unknown");
        if (last != null) {
            throw last;
        }
    }

    private void insertSendLog(
            WebhookTarget target,
            AlertEvent event,
            String status,
            int httpStatus,
            int attempts,
            String errorMessage) {
        try (Connection connection = DriverManager.getConnection(
                StorageConfig.MYSQL_URL,
                StorageConfig.MYSQL_USER,
                StorageConfig.MYSQL_PASSWORD
        );
             PreparedStatement ps = connection.prepareStatement(WebhookSendLogSql.INSERT_SEND_LOG)) {
            String mid = event.getMachineId() == null ? null : String.valueOf(event.getMachineId());
            ps.setObject(1, target.webhookId);
            ps.setString(2, target.webhookType);
            ps.setString(3, "FLINK");
            ps.setString(4, event.getAlertId());
            ps.setString(5, mid);
            ps.setString(6, event.getRiskLevel());
            ps.setString(7, status);
            if (httpStatus <= 0) {
                ps.setNull(8, java.sql.Types.INTEGER);
            } else {
                ps.setInt(8, httpStatus);
            }
            ps.setInt(9, attempts);
            ps.setString(10, errorMessage);
            ps.executeUpdate();
        } catch (Exception e) {
            System.err.println("ZEPHYR_WEBHOOK_LOG_FAIL|" + e.getMessage());
        }
    }

    private static int intEnv(String key, int defaultValue) {
        String raw = System.getenv(key);
        if (raw == null || raw.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private boolean isAllowedByRiskLevel(String riskLevel, String minRiskLevel) {
        return rank(riskLevel) >= rank(minRiskLevel);
    }

    private int rank(String riskLevel) {
        if (riskLevel == null) {
            return 0;
        }
        String normalized = riskLevel.trim().toUpperCase(Locale.ROOT);
        if ("CRITICAL".equals(normalized)) {
            return 4;
        }
        if ("HIGH".equals(normalized)) {
            return 3;
        }
        if ("MEDIUM".equals(normalized)) {
            return 2;
        }
        if ("LOW".equals(normalized)) {
            return 1;
        }
        return 0;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static class WebhookTarget {
        private final Long webhookId;
        private final String webhookUrl;
        private final String minRiskLevel;
        private final String webhookType;
        private final String signSecret;

        private WebhookTarget(Long webhookId, String webhookUrl, String minRiskLevel, String webhookType,
                String signSecret) {
            this.webhookId = webhookId;
            this.webhookUrl = webhookUrl;
            this.minRiskLevel = minRiskLevel == null || minRiskLevel.trim().isEmpty()
                ? "HIGH"
                : minRiskLevel.trim();
            this.webhookType = WebhookChannelBodies.normalizeType(webhookType);
            this.signSecret = signSecret == null || signSecret.trim().isEmpty() ? null : signSecret.trim();
        }
    }
}
