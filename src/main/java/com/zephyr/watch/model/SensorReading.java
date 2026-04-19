package com.zephyr.watch.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 原始/清洗后的传感器数据
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class SensorReading {

    private Integer machineId;
    private Integer cycle;
    private Double pressure;
    private Double temperature;
    private Double speed;
    private Long eventTime;
}