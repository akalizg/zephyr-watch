package com.zephyr.watch.common.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MaintenanceRecommendation {

    private Long recommendationId;
    private String alertId;
    private Integer machineId;
    private String action;
    private String spareParts;
    private String workOrderPriority;
    private String similarCaseId;
    private Double score;
}
