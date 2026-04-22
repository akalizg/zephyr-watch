package com.zephyr.watch.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RulPrediction {
    private Integer machineId;   // 机器ID
    private Long windowEnd;      // 预测时间点（窗口结束时间）
    private Double rul;          // 预测的剩余寿命
}