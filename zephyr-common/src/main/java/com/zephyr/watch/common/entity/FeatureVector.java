package com.zephyr.watch.common.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Window-level feature vector for the DWS layer.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class FeatureVector {

    private Integer machineId;

    private Long windowStart;
    private Long windowEnd;

    private Integer sampleCount;

    private Integer cycleStart;
    private Integer cycleEnd;

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
}
