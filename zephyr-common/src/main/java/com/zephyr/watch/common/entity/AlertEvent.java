package com.zephyr.watch.common.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AlertEvent {

    private String alertId;
    private Integer machineId;
    private Long eventTime;
    private Double riskProbability;
    private Double rul;
    private String riskLevel;
    private String alertType;
    private String message;
    private String source;
    private String status;
    private String modelVersion;
}

