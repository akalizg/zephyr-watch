package com.zephyr.watch.common.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class WebhookConfigRequest {

    private String channel;
    private String webhookUrl;
    private Boolean enabled;
}
