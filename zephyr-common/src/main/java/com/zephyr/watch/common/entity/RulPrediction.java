package com.zephyr.watch.common.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RulPrediction {
    private Integer machineId;   // 鏈哄櫒ID
    private Long windowEnd;      // 棰勬祴鏃堕棿鐐癸紙绐楀彛缁撴潫鏃堕棿锛?
    private Double rul;          // 棰勬祴鐨勫墿浣欏鍛?
}
