package com.zephyr.watch.api.webhook;

import com.zephyr.watch.common.entity.AlertEvent;
import com.zephyr.watch.common.utils.JsonUtils;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Same channel payloads as {@code com.zephyr.watch.flink.sink.WebhookChannelBodies} (Flink job);
 * duplicated here so the API test endpoint matches production sends without a cross-module dependency.
 */
public final class WebhookChannelBodies {

    private WebhookChannelBodies() {
    }

    public static byte[] toJsonUtf8(String webhookType, AlertEvent event) {
        String normalized = normalizeType(webhookType);
        switch (normalized) {
            case "WECOM":
                return wecomMarkdown(event).getBytes(StandardCharsets.UTF_8);
            case "DINGTALK":
                return dingMarkdown(event).getBytes(StandardCharsets.UTF_8);
            case "GENERIC":
            default:
                return JsonUtils.toJsonString(event).getBytes(StandardCharsets.UTF_8);
        }
    }

    /** Normalized channel key used by API dry-run and logs ({@code WECOM} / {@code DINGTALK} / {@code GENERIC} / …). */
    public static String normalizeChannelType(String webhookType) {
        return normalizeType(webhookType);
    }

    static String normalizeType(String webhookType) {
        if (webhookType == null || webhookType.trim().isEmpty()) {
            return "GENERIC";
        }
        String t = webhookType.trim().toUpperCase(Locale.ROOT);
        if ("WEWORK".equals(t) || "WECHAT_WORK".equals(t) || "QYWX".equals(t)) {
            return "WECOM";
        }
        if ("DING".equals(t)) {
            return "DINGTALK";
        }
        return t;
    }

    private static String wecomMarkdown(AlertEvent e) {
        String md = buildMarkdownSummary(e);
        return "{\"msgtype\":\"markdown\",\"markdown\":{\"content\":" + jsonString(md) + "}}";
    }

    private static String dingMarkdown(AlertEvent e) {
        String md = buildMarkdownSummary(e);
        String title = isWebhookTest(e) ? "Zephyr-Watch｜通道联调演示" : "Zephyr-Watch 告警";
        return "{\"msgtype\":\"markdown\",\"markdown\":{\"title\":" + jsonString(title) + ","
                + "\"text\":" + jsonString(md) + "}}";
    }

    private static boolean isWebhookTest(AlertEvent e) {
        return e != null && "WEBHOOK_TEST".equals(e.getAlertType());
    }

    private static String buildMarkdownSummary(AlertEvent e) {
        StringBuilder sb = new StringBuilder(256);
        if (isWebhookTest(e)) {
            sb.append("> **【联调演示】** 本条为测试推送，用于校验机器人 **JSON Schema**、**Markdown 渲染** 与 **加签 URL**；")
                    .append("**非**生产真实故障。\n\n");
        }
        sb.append("**Zephyr-Watch 设备告警**\n");
        sb.append(">级别: ").append(nullToDash(e.getRiskLevel())).append("\n");
        sb.append(">机器: ").append(e.getMachineId() == null ? "-" : e.getMachineId()).append("\n");
        sb.append(">风险概率: ").append(e.getRiskProbability() == null ? "-" : e.getRiskProbability()).append("\n");
        sb.append(">RUL: ").append(e.getRul() == null ? "-" : e.getRul()).append("\n");
        sb.append(">类型: ").append(nullToDash(e.getAlertType())).append("\n");
        sb.append(">模型: ").append(nullToDash(e.getModelVersion())).append("\n");
        sb.append(">时间: ").append(e.getEventTime() == null ? "-" : e.getEventTime()).append("\n");
        sb.append("**摘要:** ").append(nullToDash(e.getMessage()));
        return sb.toString();
    }

    private static String nullToDash(String s) {
        return s == null || s.isEmpty() ? "-" : s;
    }

    private static String jsonString(String raw) {
        StringBuilder out = new StringBuilder(raw.length() + 16);
        out.append('"');
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '\\':
                    out.append("\\\\");
                    break;
                case '"':
                    out.append("\\\"");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        out.append(' ');
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
        return out.toString();
    }
}
