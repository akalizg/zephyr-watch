package com.zephyr.watch.common.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RulPrediction {
    private Integer machineId;
    private Long windowEnd;
    private Double rul;
}
