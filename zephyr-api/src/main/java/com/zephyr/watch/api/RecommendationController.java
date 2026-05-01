package com.zephyr.watch.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/recommendations")
public class RecommendationController {

    @GetMapping
    public Map<String, Object> recommendations() {
        return ApiResponse.ok("recommendation", "Recommendation result endpoint is ready");
    }
}
