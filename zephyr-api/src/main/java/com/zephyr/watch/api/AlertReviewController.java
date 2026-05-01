package com.zephyr.watch.api;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/alerts")
public class AlertReviewController {

    @PostMapping("/review")
    public Map<String, Object> reviewAlert() {
        return ApiResponse.ok("alert-review", "Alert review endpoint is ready");
    }
}
