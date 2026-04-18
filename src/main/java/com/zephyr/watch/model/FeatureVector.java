package com.zephyr.watch.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 窗口特征向量
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class FeatureVector {

    private Integer machineId;
    private Long windowStart;
    private Long windowEnd;

    private Integer sampleCount;

    private Double pressureMax;
    private Double pressureAvg;
    private Double temperatureAvg;
    private Double speedAvg;
}