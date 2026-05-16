package com.zephyr.watch.api;

import com.zephyr.watch.api.webhook.WebhookChannelBodies;
import com.zephyr.watch.api.webhook.WebhookDryRunMetadata;
import com.zephyr.watch.common.dto.WebhookConfigRequest;
import com.zephyr.watch.common.entity.AlertEvent;
import com.zephyr.watch.common.webhook.WebhookHttpTransport;
import com.zephyr.watch.common.webhook.WebhookSendLogSql;
import com.zephyr.watch.common.webhook.WebhookUrlSigner;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/webhooks")
public class WebhookConfigController {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Default test summary when no {@code message} is passed: reads as a deliberate demo scenario
     * (答辩 / Postman) rather than a bare English placeholder.
     */
    private static final String DEFAULT_WEBHOOK_TEST_SUMMARY =
            "【通道联调】一号机组轴承温度风险告警（模拟）｜用于演示「级别 / 机器 / 风险概率 / 摘要」"
                    + "在钉钉与企业微信 Markdown 中的展示；数据为测试构造，非现场真实故障。";

    private final JdbcTemplate jdbcTemplate;

    public WebhookConfigController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping
    public Map<String, Object> webhooks() {
        List<Map<String, Object>> configs = jdbcTemplate.queryForList(
                "SELECT webhook_id AS webhookId, name, webhook_type AS webhookType, "
                        + "webhook_url AS webhookUrl, min_risk_level AS minRiskLevel, enabled, "
                        + "CASE WHEN webhook_sign_secret IS NOT NULL AND TRIM(webhook_sign_secret) <> '' "
                        + "THEN 1 ELSE 0 END AS signSecretConfigured, "
                        + "created_at AS createdAt, updated_at AS updatedAt "
                        + "FROM webhook_config ORDER BY webhook_id DESC"
        );
        return ApiResponse.ok("webhook-config", configs);
    }

    @GetMapping("/{id}/deliveries")
    public Map<String, Object> deliveries(
            @PathVariable Long id,
            @RequestParam(value = "page", required = false, defaultValue = "0") int page,
            @RequestParam(value = "size", required = false, defaultValue = "20") int size) {
        int safeSize = Math.min(100, Math.max(1, size));
        int safePage = Math.max(0, page);
        int offset = safePage * safeSize;
        Long totalObj = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM webhook_send_log WHERE webhook_id = ?",
                Long.class,
                id
        );
        long total = totalObj == null ? 0L : totalObj;
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT log_id AS logId, webhook_id AS webhookId, webhook_type AS webhookType, source, "
                        + "event_id AS eventId, machine_id AS machineId, risk_level AS riskLevel, status, "
                        + "http_status AS httpStatus, attempts, error_message AS errorMessage, "
                        + "created_at AS createdAt "
                        + "FROM webhook_send_log WHERE webhook_id = ? ORDER BY log_id DESC LIMIT ? OFFSET ?",
                id,
                safeSize,
                offset
        );
        Map<String, Object> pageInfo = new LinkedHashMap<>();
        pageInfo.put("items", rows);
        pageInfo.put("page", safePage);
        pageInfo.put("size", safeSize);
        pageInfo.put("total", total);
        return ApiResponse.ok("webhook-send-log", pageInfo);
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody WebhookConfigRequest request) {
        validate(request);
        String secret = StringUtils.hasText(request.getWebhookSignSecret())
                ? request.getWebhookSignSecret().trim()
                : null;
        int affectedRows = jdbcTemplate.update(
                "INSERT INTO webhook_config (name, webhook_type, webhook_url, webhook_sign_secret, "
                        + "min_risk_level, enabled) VALUES (?, ?, ?, ?, ?, ?)",
                request.getName().trim(),
                defaultText(request.getWebhookType(), "GENERIC"),
                request.getWebhookUrl().trim(),
                secret,
                defaultText(request.getMinRiskLevel(), "HIGH"),
                toEnabled(request)
        );
        return ApiResponse.ok("webhook-config-create", affectedRows);
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody WebhookConfigRequest request) {
        validate(request);
        boolean clear = Boolean.TRUE.equals(request.getClearWebhookSignSecret());
        boolean setNew = StringUtils.hasText(request.getWebhookSignSecret());
        if (clear && setNew) {
            throw new IllegalArgumentException("Use either clearWebhookSignSecret or webhookSignSecret, not both");
        }
        int affectedRows;
        if (clear) {
            affectedRows = jdbcTemplate.update(
                    "UPDATE webhook_config SET name = ?, webhook_type = ?, webhook_url = ?, "
                            + "webhook_sign_secret = NULL, min_risk_level = ?, enabled = ? WHERE webhook_id = ?",
                    request.getName().trim(),
                    defaultText(request.getWebhookType(), "GENERIC"),
                    request.getWebhookUrl().trim(),
                    defaultText(request.getMinRiskLevel(), "HIGH"),
                    toEnabled(request),
                    id
            );
        } else if (setNew) {
            affectedRows = jdbcTemplate.update(
                    "UPDATE webhook_config SET name = ?, webhook_type = ?, webhook_url = ?, "
                            + "webhook_sign_secret = ?, min_risk_level = ?, enabled = ? WHERE webhook_id = ?",
                    request.getName().trim(),
                    defaultText(request.getWebhookType(), "GENERIC"),
                    request.getWebhookUrl().trim(),
                    request.getWebhookSignSecret().trim(),
                    defaultText(request.getMinRiskLevel(), "HIGH"),
                    toEnabled(request),
                    id
            );
        } else {
            affectedRows = jdbcTemplate.update(
                    "UPDATE webhook_config SET name = ?, webhook_type = ?, webhook_url = ?, "
                            + "min_risk_level = ?, enabled = ? WHERE webhook_id = ?",
                    request.getName().trim(),
                    defaultText(request.getWebhookType(), "GENERIC"),
                    request.getWebhookUrl().trim(),
                    defaultText(request.getMinRiskLevel(), "HIGH"),
                    toEnabled(request),
                    id
            );
        }
        return ApiResponse.ok("webhook-config-update", affectedRows);
    }

    @PostMapping("/{id}/test")
    public Map<String, Object> test(
            @PathVariable Long id,
            @RequestParam(value = "dryRun", required = false, defaultValue = "false") boolean dryRun,
            @RequestParam(value = "message", required = false) String message,
            @RequestBody(required = false) WebhookTestBody testBody) {
        Map<String, Object> config = jdbcTemplate.queryForMap(
                "SELECT webhook_url, webhook_type, webhook_sign_secret FROM webhook_config WHERE webhook_id = ?",
                id
        );
        String webhookUrl = getRowString(config, "webhook_url", "webhookUrl");
        if (!StringUtils.hasText(webhookUrl)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "webhook_url is missing in database row (check column mapping); keys=" + config.keySet()
            );
        }
        String webhookType = getRowString(config, "webhook_type", "webhookType");
        if (!StringUtils.hasText(webhookType)) {
            webhookType = "GENERIC";
        }
        String signSecret = getRowString(config, "webhook_sign_secret", "webhookSignSecret");
        boolean signConfigured = StringUtils.hasText(signSecret);
        String signedUrl = WebhookUrlSigner.appendSignQuery(webhookUrl.trim(), signSecret);
        String custom = firstNonBlank(message, testBody != null ? testBody.message : null);
        String summary = StringUtils.hasText(custom) ? truncateMessage(custom.trim()) : DEFAULT_WEBHOOK_TEST_SUMMARY;
        AlertEvent probe = new AlertEvent(
                "webhook-test-" + System.currentTimeMillis(),
                0,
                System.currentTimeMillis(),
                0.95D,
                12.0D,
                "CRITICAL",
                "WEBHOOK_TEST",
                summary,
                "API",
                "TEST",
                "webhook-test"
        );
        byte[] payloadUtf8 = WebhookChannelBodies.toJsonUtf8(webhookType, probe);
        if (dryRun) {
            Map<String, Object> info = new LinkedHashMap<>();
            String normalized = WebhookChannelBodies.normalizeChannelType(webhookType);
            info.put("webhookType", webhookType);
            info.put("normalizedChannel", normalized);
            info.put("channelDemoTitle", WebhookDryRunMetadata.channelDemoTitle(normalized));
            info.put("howToExplainInDefense", WebhookDryRunMetadata.defenseOneLiner(normalized));
            info.put("diffVsGenericJson", WebhookDryRunMetadata.diffVsGeneric(normalized));
            info.put("bodyUtf8", new String(payloadUtf8, StandardCharsets.UTF_8));
            try {
                JsonNode tree = JSON.readTree(payloadUtf8);
                info.put("bodyJson", tree);
            } catch (Exception e) {
                info.put("bodyJson", null);
                info.put("bodyJsonParseError", e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            info.put("signSecretConfigured", signConfigured);
            info.put("requestUrlForDisplay", redactSignInUrl(signedUrl));
            info.put("skippedPush", Boolean.TRUE);
            info.put(
                    "postmanTip",
                    "答辩建议：同一接口先 dryRun=true 并排对比「钉钉 / 企业微信 / GENERIC」的 bodyJson 与 diffVsGenericJson，"
                            + "再发一次真实 test；群内文案已含「联调演示」标识，避免被误认为真实事故。"
            );
            return ApiResponse.ok("webhook-config-test-dry-run", info);
        }
        int maxAttempts = Math.max(1, intEnv("ZEPHYR_WEBHOOK_MAX_ATTEMPTS", 2));
        Exception last = null;
        int lastHttp = 0;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                WebhookHttpTransport.Result r = WebhookHttpTransport.postJson(
                        signedUrl,
                        payloadUtf8,
                        intEnv("ZEPHYR_WEBHOOK_CONNECT_TIMEOUT_MS", 5000),
                        intEnv("ZEPHYR_WEBHOOK_READ_TIMEOUT_MS", 5000)
                );
                lastHttp = r.getHttpStatus();
                if (lastHttp < 400 && r.isVendorOk()) {
                    insertSendLog(id, webhookType, "API", probe, "SUCCESS", lastHttp, attempt, null);
                    Map<String, Object> ok = new LinkedHashMap<>();
                    ok.put("httpStatus", lastHttp);
                    ok.put("webhookType", webhookType);
                    ok.put("normalizedChannel", WebhookChannelBodies.normalizeChannelType(webhookType));
                    ok.put(
                            "note",
                            "推送已成功。群内消息含「联调演示」与结构化字段说明；答辩可先 dryRun 展示 JSON 差异再实发。"
                    );
                    return ApiResponse.ok("webhook-config-test", ok);
                }
                last = new IllegalStateException(
                        "Webhook non-ok, status=" + lastHttp + " body=" + r.getResponseBody()
                );
            } catch (Exception e) {
                last = e;
            }
            if (attempt < maxAttempts) {
                try {
                    Thread.sleep(200L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    insertSendLog(id, webhookType, "API", probe, "FAILED", lastHttp, attempt,
                            truncateMessage("interrupted during webhook test retry"));
                    throw new ResponseStatusException(
                            HttpStatus.BAD_GATEWAY,
                            "Webhook test interrupted",
                            ie
                    );
                }
            }
        }
        insertSendLog(id, webhookType, "API", probe, "FAILED", lastHttp, maxAttempts,
                last != null ? truncateMessage(last.getMessage()) : "unknown");
        throw new ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "Webhook test HTTP failed: " + (last != null ? last.getMessage() : "unknown"),
                last
        );
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        jdbcTemplate.update(
                "DELETE FROM webhook_config WHERE webhook_id = ?",
                id
        );
        return ApiResponse.ok("webhook-config-delete", id);
    }

    private void insertSendLog(
            Long webhookId,
            String webhookType,
            String source,
            AlertEvent event,
            String status,
            int httpStatus,
            int attempts,
            String errorMessage) {
        try {
            String mid = event.getMachineId() == null ? null : String.valueOf(event.getMachineId());
            jdbcTemplate.update(
                    WebhookSendLogSql.INSERT_SEND_LOG,
                    webhookId,
                    webhookType,
                    source,
                    event.getAlertId(),
                    mid,
                    event.getRiskLevel(),
                    status,
                    httpStatus > 0 ? httpStatus : null,
                    attempts,
                    errorMessage
            );
        } catch (Exception e) {
            System.err.println("ZEPHYR_WEBHOOK_LOG_FAIL|" + e.getMessage());
        }
    }

    private void validate(WebhookConfigRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request body is required");
        }
        if (!StringUtils.hasText(request.getName())) {
            throw new IllegalArgumentException("name is required");
        }
        if (!StringUtils.hasText(request.getWebhookUrl())) {
            throw new IllegalArgumentException("webhookUrl is required");
        }
    }

    private String defaultText(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value.trim() : defaultValue;
    }

    private int toEnabled(WebhookConfigRequest request) {
        return request.getEnabled() == null || request.getEnabled() ? 1 : 0;
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

    private static String truncateMessage(String msg) {
        if (msg == null) {
            return null;
        }
        return msg.length() <= 1000 ? msg : msg.substring(0, 1000);
    }

    /** Query {@code message} first, then JSON body {@code message} (Apifox 常用 Body 传参). */
    private static String firstNonBlank(String a, String b) {
        if (StringUtils.hasText(a)) {
            return a;
        }
        if (StringUtils.hasText(b)) {
            return b;
        }
        return null;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class WebhookTestBody {
        /** JSON field {@code "message"}; public for Jackson binding without extra getters. */
        public String message;
    }

    private static String redactSignInUrl(String url) {
        if (url == null) {
            return null;
        }
        int i = url.indexOf("sign=");
        if (i < 0) {
            return url;
        }
        int start = i + 5;
        int end = url.indexOf('&', start);
        if (end < 0) {
            return url.substring(0, start) + "***";
        }
        return url.substring(0, start) + "***" + url.substring(end);
    }

    /**
     * JdbcTemplate {@code queryForMap} keys vary by driver (e.g. {@code webhook_url} vs {@code webhookUrl});
     * avoid {@code String.valueOf(null)} becoming the literal URL {@code null}.
     */
    private static String getRowString(Map<String, Object> row, String... candidateKeys) {
        for (String key : candidateKeys) {
            if (!row.containsKey(key)) {
                continue;
            }
            Object v = row.get(key);
            if (v != null) {
                String s = v.toString().trim();
                if (!s.isEmpty()) {
                    return s;
                }
            }
        }
        for (Map.Entry<String, Object> e : row.entrySet()) {
            String k = e.getKey();
            if (k == null || e.getValue() == null) {
                continue;
            }
            for (String wanted : candidateKeys) {
                if (wanted != null && wanted.equalsIgnoreCase(k)) {
                    String s = e.getValue().toString().trim();
                    if (!s.isEmpty()) {
                        return s;
                    }
                }
            }
        }
        return null;
    }
}
