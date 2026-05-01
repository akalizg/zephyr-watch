package com.zephyr.watch.common.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AlertReviewRequest {

    private String alertId;
    private String reviewer;
    private String reviewLabel;
    private String reviewComment;
}
