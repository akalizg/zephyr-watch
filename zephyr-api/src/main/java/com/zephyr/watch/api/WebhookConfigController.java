package com.zephyr.watch.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/webhooks")
public class WebhookConfigController {

    @GetMapping
    public Map<String, Object> webhooks() {
        return ApiResponse.ok("webhook-config", "Webhook configuration endpoint is ready");
    }
}
