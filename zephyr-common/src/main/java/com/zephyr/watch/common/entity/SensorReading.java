package com.zephyr.watch.common.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Raw or cleaned sensor reading.
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
