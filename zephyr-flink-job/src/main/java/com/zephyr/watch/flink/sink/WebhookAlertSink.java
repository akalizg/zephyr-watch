package com.zephyr.watch.flink.sink;

import com.zephyr.watch.common.constants.StorageConfig;
import com.zephyr.watch.common.entity.AlertEvent;
import com.zephyr.watch.common.utils.JsonUtils;
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class WebhookAlertSink extends RichSinkFunction<AlertEvent> {

    private final String webhookUrl;

    public WebhookAlertSink() {
        this(StorageConfig.ALERT_WEBHOOK_URL);
    }

    public WebhookAlertSink(String webhookUrl) {
        this.webhookUrl = webhookUrl;
    }

    @Override
    public void invoke(AlertEvent value, Context context) throws Exception {
        if (webhookUrl == null || webhookUrl.trim().isEmpty()) {
            return;
        }

        byte[] payload = JsonUtils.toJsonString(value).getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = (HttpURLConnection) new URL(webhookUrl).openConnection();
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(3000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json;charset=UTF-8");

        try (OutputStream os = connection.getOutputStream()) {
            os.write(payload);
        }

        int status = connection.getResponseCode();
        if (status >= 400) {
            throw new IllegalStateException("Webhook alert push failed, status=" + status);
        }
    }
}

