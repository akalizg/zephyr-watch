package com.zephyr.watch.bean;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 工业传感器数据模型 (NASA 涡扇发动机)
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class SensorReading {
    private Integer machineId; // 机组ID (第1列)
    private Integer cycle;     // 运行周期 (第2列)
    private Double s2;         // 传感器2：压力 (第7列)
    private Double s3;         // 传感器3：温度 (第8列)
    private Double s4;         // 传感器4：转速 (第9列)
    private Long ts;           // 系统时间戳 (用于模拟实时流)
}