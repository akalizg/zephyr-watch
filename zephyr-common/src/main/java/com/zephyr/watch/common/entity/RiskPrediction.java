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

    private Integer sampleCount;
    private Double pressureMin;
    private Double pressureMax;
    private Double pressureAvg;
    private Double pressureStd;
    private Double pressureTrend;
    private Double temperatureMin;
    private Double temperatureMax;
    private Double temperatureAvg;
    private Double temperatureStd;
    private Double temperatureTrend;
    private Double speedMin;
    private Double speedMax;
    private Double speedAvg;
    private Double speedStd;
    private Double speedTrend;

    public RiskPrediction(String predictionId,
                          Integer machineId,
                          Long windowStart,
                          Long windowEnd,
                          Integer cycleStart,
                          Integer cycleEnd,
                          Double rul,
                          Double riskProbability,
                          Integer riskLabel,
                          String riskLevel,
                          String modelVersion,
                          Long eventTime) {
        this.predictionId = predictionId;
        this.machineId = machineId;
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
        this.cycleStart = cycleStart;
        this.cycleEnd = cycleEnd;
        this.rul = rul;
        this.riskProbability = riskProbability;
        this.riskLabel = riskLabel;
        this.riskLevel = riskLevel;
        this.modelVersion = modelVersion;
        this.eventTime = eventTime;
    }
}

