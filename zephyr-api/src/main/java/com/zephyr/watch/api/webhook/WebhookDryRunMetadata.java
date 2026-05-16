package com.zephyr.watch.api.webhook;

/**
 * Human-readable hints for {@code dryRun=true} on {@code POST /api/webhooks/{id}/test}:
 * demo titles and how each channel differs from {@code GENERIC} JSON.
 */
public final class WebhookDryRunMetadata {

    private WebhookDryRunMetadata() {
    }

    public static String channelDemoTitle(String normalizedChannel) {
        if (normalizedChannel == null) {
            return "未知通道";
        }
        switch (normalizedChannel) {
            case "WECOM":
                return "企业微信机器人 Markdown（msgtype=markdown）";
            case "DINGTALK":
                return "钉钉机器人 Markdown（msgtype=markdown，含 title）";
            case "GENERIC":
            default:
                return "通用 JSON（AlertEvent 全量字段）";
        }
    }

    public static String defenseOneLiner(String normalizedChannel) {
        if (normalizedChannel == null) {
            return "请检查 webhookType 配置。";
        }
        switch (normalizedChannel) {
            case "WECOM":
                return "企业微信只接受固定 schema；这里把告警压成 markdown content，避免群里直接刷原始 AlertEvent。";
            case "DINGTALK":
                return "钉钉 markdown 需要 title 与 text；与 GENERIC 的结构差异在 dryRun 的 bodyJson 里可直接对比。";
            case "GENERIC":
            default:
                return "GENERIC 与线上一致：序列化 AlertEvent，便于自建网关或脚本二次处理。";
        }
    }

    public static String diffVsGeneric(String normalizedChannel) {
        if (normalizedChannel == null) {
            return "无法对比：通道类型未识别。";
        }
        switch (normalizedChannel) {
            case "WECOM":
                return "GENERIC 顶层为 alertId、machineId 等字段；WECOM 顶层为 msgtype 与 markdown.content（摘要字符串）。";
            case "DINGTALK":
                return "GENERIC 为平铺字段；DINGTALK 为 markdown.title + markdown.text；测试告警的 title 会显示「通道联调演示」。";
            case "GENERIC":
            default:
                return "无转换：与 Flink WebhookAlertSink 的 GENERIC 分支一致，直接输出 AlertEvent JSON。";
        }
    }
}
