package com.zephyr.watch.api;

import com.zephyr.watch.common.dto.WebhookConfigRequest;
import com.zephyr.watch.common.entity.AlertEvent;
import com.zephyr.watch.common.utils.JsonUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/webhooks")
public class WebhookConfigController {

    private final JdbcTemplate jdbcTemplate;

    public WebhookConfigController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping
    public Map<String, Object> webhooks() {
        List<Map<String, Object>> configs = jdbcTemplate.queryForList(
                "SELECT webhook_id AS webhookId, name, webhook_type AS webhookType, "
                        + "webhook_url AS webhookUrl, min_risk_level AS minRiskLevel, enabled, "
                        + "created_at AS createdAt, updated_at AS updatedAt "
                        + "FROM webhook_config ORDER BY webhook_id DESC"
        );
        return ApiResponse.ok("webhook-config", configs);
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody WebhookConfigRequest request) {
        validate(request);
        int affectedRows = jdbcTemplate.update(
                "INSERT INTO webhook_config (name, webhook_type, webhook_url, min_risk_level, enabled) "
                        + "VALUES (?, ?, ?, ?, ?)",
                request.getName().trim(),
                defaultText(request.getWebhookType(), "GENERIC"),
                request.getWebhookUrl().trim(),
                defaultText(request.getMinRiskLevel(), "HIGH"),
                toEnabled(request)
        );
        return ApiResponse.ok("webhook-config-create", affectedRows);
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody WebhookConfigRequest request) {
        validate(request);
        int affectedRows = jdbcTemplate.update(
                "UPDATE webhook_config SET name = ?, webhook_type = ?, webhook_url = ?, "
                        + "min_risk_level = ?, enabled = ? WHERE webhook_id = ?",
                request.getName().trim(),
                defaultText(request.getWebhookType(), "GENERIC"),
                request.getWebhookUrl().trim(),
                defaultText(request.getMinRiskLevel(), "HIGH"),
                toEnabled(request),
                id
        );
        return ApiResponse.ok("webhook-config-update", affectedRows);
    }

    @PostMapping("/{id}/test")
    public Map<String, Object> test(@PathVariable Long id) throws Exception {
        Map<String, Object> config = jdbcTemplate.queryForMap(
                "SELECT webhook_url AS webhookUrl FROM webhook_config WHERE webhook_id = ?",
                id
        );
        AlertEvent probe = new AlertEvent(
                "webhook-test-" + System.currentTimeMillis(),
                0,
                System.currentTimeMillis(),
                0.95D,
                12.0D,
                "CRITICAL",
                "WEBHOOK_TEST",
                "Zephyr-Watch webhook test alert",
                "API",
                "TEST",
                "webhook-test"
        );
        int status = postJson(String.valueOf(config.get("webhookUrl")), JsonUtils.toJsonString(probe));
        return ApiResponse.ok("webhook-config-test", status);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        jdbcTemplate.update(
                "DELETE FROM webhook_config WHERE webhook_id = ?",
                id
        );
        return ApiResponse.ok("webhook-config-delete", id);
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

    private int postJson(String webhookUrl, String payload) throws Exception {
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = (HttpURLConnection) new URL(webhookUrl).openConnection();
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(3000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
        connection.setFixedLengthStreamingMode(body.length);
        try (OutputStream os = connection.getOutputStream()) {
            os.write(body);
        }
        return connection.getResponseCode();
    }
}
