package com.zephyr.watch.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/risks")
public class RiskQueryController {

    @GetMapping("/{machineId}")
    public Map<String, Object> latestRisk(@PathVariable Integer machineId) {
        return ApiResponse.ok("device-risk", "Query latest risk for machine " + machineId);
    }
}
