package com.zephyr.watch.api;

import com.zephyr.watch.api.service.RiskQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/risks")
public class RiskQueryController {

    private final RiskQueryService riskQueryService;

    public RiskQueryController(RiskQueryService riskQueryService) {
        this.riskQueryService = riskQueryService;
    }

    @GetMapping("/{machineId}")
    public Map<String, Object> latestRisk(@PathVariable Integer machineId) {
        return ApiResponse.ok("device-risk", riskQueryService.latestRisk(machineId));
    }

    @GetMapping("/{machineId}/history")
    public Map<String, Object> riskHistory(@PathVariable Integer machineId,
                                           @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok("device-risk-history", riskQueryService.riskHistory(machineId, limit));
    }
}
