package com.zephyr.watch.api;

import com.zephyr.watch.api.service.DashboardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        return ApiResponse.ok("dashboard-overview", dashboardService.overview());
    }

    @GetMapping("/risk-level-distribution")
    public Map<String, Object> riskLevelDistribution() {
        return ApiResponse.ok("dashboard-risk-level-distribution", dashboardService.riskLevelDistribution());
    }

    @GetMapping("/alert-type-distribution")
    public Map<String, Object> alertTypeDistribution() {
        return ApiResponse.ok("dashboard-alert-type-distribution", dashboardService.alertTypeDistribution());
    }

    @GetMapping("/top-risk-machines")
    public Map<String, Object> topRiskMachines(@RequestParam(defaultValue = "10") int limit) {
        return ApiResponse.ok("dashboard-top-risk-machines", dashboardService.topRiskMachines(limit));
    }

    @GetMapping("/latest-predictions")
    public Map<String, Object> latestPredictions(@RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok("dashboard-latest-predictions", dashboardService.latestPredictions(limit));
    }

    @GetMapping("/latest-alerts")
    public Map<String, Object> latestAlerts(@RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok("dashboard-latest-alerts", dashboardService.latestAlerts(limit));
    }

    @GetMapping("/latest-recommendations")
    public Map<String, Object> latestRecommendations(@RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok("dashboard-latest-recommendations", dashboardService.latestRecommendations(limit));
    }
}
