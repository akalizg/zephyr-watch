package com.zephyr.watch.api;

import com.zephyr.watch.api.service.AlertReviewService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/learning")
public class LearningController {

    private final AlertReviewService alertReviewService;

    public LearningController(AlertReviewService alertReviewService) {
        this.alertReviewService = alertReviewService;
    }

    @GetMapping("/review-labels")
    public Map<String, Object> reviewLabels(@RequestParam(defaultValue = "500") int limit) {
        return ApiResponse.ok("review-label-export", alertReviewService.reviewLabels(limit));
    }

    @GetMapping("/feedback-training-samples")
    public Map<String, Object> feedbackTrainingSamples(@RequestParam(defaultValue = "500") int limit) {
        return ApiResponse.ok("feedback-training-sample-export", alertReviewService.feedbackTrainingSamples(limit));
    }
}
