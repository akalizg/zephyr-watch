package com.zephyr.watch.api;

import com.zephyr.watch.api.service.RecommendationQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/recommendations")
public class RecommendationController {

    private final RecommendationQueryService recommendationQueryService;

    public RecommendationController(RecommendationQueryService recommendationQueryService) {
        this.recommendationQueryService = recommendationQueryService;
    }

    @GetMapping
    public Map<String, Object> recommendations(@RequestParam(required = false) Integer machineId,
                                               @RequestParam(required = false) String alertId,
                                               @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(
                "recommendation",
                recommendationQueryService.recommendations(machineId, alertId, limit)
        );
    }
}
