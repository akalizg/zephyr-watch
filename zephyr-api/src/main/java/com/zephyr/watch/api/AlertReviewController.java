package com.zephyr.watch.api;

import com.zephyr.watch.api.service.AlertReviewService;
import com.zephyr.watch.common.dto.AlertReviewRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/alerts")
public class AlertReviewController {

    private final AlertReviewService alertReviewService;

    public AlertReviewController(AlertReviewService alertReviewService) {
        this.alertReviewService = alertReviewService;
    }

    @GetMapping
    public Map<String, Object> alerts(@RequestParam(required = false) String status,
                                      @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok("alert-list", alertReviewService.alerts(status, limit));
    }

    @PostMapping("/review")
    public Map<String, Object> reviewAlert(@RequestBody AlertReviewRequest request) {
        return ApiResponse.ok("alert-review", alertReviewService.review(request));
    }
}
