package com.zephyr.watch.api;

import com.zephyr.watch.common.dto.WebhookConfigRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
                "SELECT channel, webhook_url AS webhookUrl, enabled, updated_at AS updatedAt "
                        + "FROM webhook_config ORDER BY channel"
        );
        return ApiResponse.ok("webhook-config", configs);
    }

    @PostMapping
    public Map<String, Object> upsert(@RequestBody WebhookConfigRequest request) {
        if (request == null || request.getChannel() == null || request.getChannel().trim().isEmpty()) {
            throw new IllegalArgumentException("channel is required");
        }
        if (request.getWebhookUrl() == null || request.getWebhookUrl().trim().isEmpty()) {
            throw new IllegalArgumentException("webhookUrl is required");
        }
        Boolean enabled = request.getEnabled() == null ? Boolean.TRUE : request.getEnabled();
        jdbcTemplate.update(
                "INSERT INTO webhook_config (channel, webhook_url, enabled) VALUES (?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE webhook_url = VALUES(webhook_url), enabled = VALUES(enabled)",
                request.getChannel().trim(),
                request.getWebhookUrl().trim(),
                enabled ? 1 : 0
        );
        return ApiResponse.ok("webhook-config-upsert", request);
    }
}
