package com.zephyr.watch.common.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class WebhookConfigRequest {

    private Long webhookId;
    private String name;
    private String webhookType;
    private String webhookUrl;
    private String minRiskLevel;
    private Boolean enabled;
}
