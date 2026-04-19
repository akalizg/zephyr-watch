package com.zephyr.watch.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 原始传感器数据
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class SensorReading {

    private Integer machineId;     // 机组ID
    private Integer cycle;         // 运行周期
    private Double pressure;       // 压力
    private Double temperature;    // 温度
    private Double speed;          // 转速
    private Long eventTime;        // 事件时间
}