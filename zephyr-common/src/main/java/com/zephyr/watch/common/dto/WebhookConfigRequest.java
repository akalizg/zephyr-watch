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
    /** WeCom / DingTalk 加签密钥；创建时可选。 */
    private String webhookSignSecret;
    /**
     * 更新时：为 {@code true} 则清空库中密钥；与 {@link #webhookSignSecret} 互斥。
     */
    private Boolean clearWebhookSignSecret;
    private String minRiskLevel;
    private Boolean enabled;
}
