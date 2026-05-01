package com.zephyr.watch.common.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 鍘熷/娓呮礂鍚庣殑浼犳劅鍣ㄦ暟鎹?
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
