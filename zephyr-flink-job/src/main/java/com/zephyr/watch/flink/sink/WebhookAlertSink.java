package com.zephyr.watch.flink.sink;

import com.zephyr.watch.common.constants.StorageConfig;
import com.zephyr.watch.common.entity.AlertEvent;
import com.zephyr.watch.common.utils.JsonUtils;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class WebhookAlertSink extends RichSinkFunction<AlertEvent> {

    private final String webhookUrl;
    private transient List<WebhookTarget> targets;

    public WebhookAlertSink() {
        this(StorageConfig.ALERT_WEBHOOK_URL);
    }

    public WebhookAlertSink(String webhookUrl) {
        this.webhookUrl = webhookUrl;
    }

    @Override
    public void open(Configuration parameters) {
        this.targets = loadTargets();
    }

    @Override
    public void invoke(AlertEvent value, Context context) throws Exception {
        if (targets == null || targets.isEmpty()) {
            return;
        }

        byte[] payload = JsonUtils.toJsonString(value).getBytes(StandardCharsets.UTF_8);
        for (WebhookTarget target : targets) {
            if (!isAllowedByRiskLevel(value.getRiskLevel(), target.minRiskLevel)) {
                continue;
            }
            post(target.webhookUrl, payload);
        }
    }

    private List<WebhookTarget> loadTargets() {
        List<WebhookTarget> loaded = new ArrayList<>();
        if (webhookUrl != null && !webhookUrl.trim().isEmpty()) {
            loaded.add(new WebhookTarget(webhookUrl.trim(), "HIGH"));
        }

        try (Connection connection = DriverManager.getConnection(
            StorageConfig.MYSQL_URL,
            StorageConfig.MYSQL_USER,
            StorageConfig.MYSQL_PASSWORD
        );
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                 "SELECT webhook_url, min_risk_level FROM webhook_config WHERE enabled = 1"
             )) {
            while (rs.next()) {
                String url = rs.getString("webhook_url");
                if (url != null && !url.trim().isEmpty()) {
                    loaded.add(new WebhookTarget(url.trim(), rs.getString("min_risk_level")));
                }
            }
        } catch (Exception e) {
            if (loaded.isEmpty()) {
                throw new IllegalStateException("Failed to load webhook_config from MySQL", e);
            }
        }
        return loaded;
    }

    private void post(String url, byte[] payload) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(3000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json;charset=UTF-8");

        try (OutputStream os = connection.getOutputStream()) {
            os.write(payload);
        }

        int status = connection.getResponseCode();
        if (status >= 400) {
            throw new IllegalStateException("Webhook alert push failed, status=" + status);
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

    private static class WebhookTarget {
        private final String webhookUrl;
        private final String minRiskLevel;

        private WebhookTarget(String webhookUrl, String minRiskLevel) {
            this.webhookUrl = webhookUrl;
            this.minRiskLevel = minRiskLevel == null || minRiskLevel.trim().isEmpty()
                ? "HIGH"
                : minRiskLevel.trim();
        }
    }
}

