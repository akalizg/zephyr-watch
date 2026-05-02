package com.zephyr.watch.common.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RiskPrediction {

    private String predictionId;
    private Integer machineId;
    private Long windowStart;
    private Long windowEnd;
    private Integer cycleStart;
    private Integer cycleEnd;
    private Double rul;
    private Double riskProbability;
    private Integer riskLabel;
    private String riskLevel;
    private String modelVersion;
    private Long eventTime;
}

