package com.zephyr.watch.common.webhook;

import com.alibaba.fastjson2.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * Minimal JSON POST for robot webhooks; treats HTTP 2xx with vendor {@code errcode != 0} as failure.
 */
public final class WebhookHttpTransport {

    private WebhookHttpTransport() {
    }

    public static Result postJson(String webhookUrl, byte[] body, int connectTimeoutMs, int readTimeoutMs)
            throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(webhookUrl).toURL().openConnection();
        connection.setConnectTimeout(connectTimeoutMs);
        connection.setReadTimeout(readTimeoutMs);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
        connection.setFixedLengthStreamingMode(body.length);
        try (OutputStream os = connection.getOutputStream()) {
            os.write(body);
        }
        int status = connection.getResponseCode();
        String responseBody = readResponseBody(connection, status);
        Integer vendorErr = parseVendorErrcode(responseBody);
        boolean vendorOk = vendorErr == null || vendorErr == 0;
        return new Result(status, responseBody, vendorOk);
    }

    private static String readResponseBody(HttpURLConnection connection, int status) throws IOException {
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (stream == null) {
            return "";
        }
        try (InputStream in = stream; ByteArrayOutputStream buf = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) >= 0) {
                buf.write(chunk, 0, n);
            }
            // Java 8: ByteArrayOutputStream.toString(Charset) is Java 10+
            return new String(buf.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /**
     * @return {@code null} if JSON has no {@code errcode} (treat as non-vendor response).
     */
    static Integer parseVendorErrcode(String body) {
        if (body == null) {
            return null;
        }
        String trimmed = body.trim();
        if (!trimmed.startsWith("{")) {
            return null;
        }
        try {
            JSONObject o = JSONObject.parseObject(trimmed);
            if (o == null || !o.containsKey("errcode")) {
                return null;
            }
            return o.getIntValue("errcode");
        } catch (Exception e) {
            return null;
        }
    }

    public static final class Result {
        private final int httpStatus;
        private final String responseBody;
        private final boolean vendorOk;

        public Result(int httpStatus, String responseBody, boolean vendorOk) {
            this.httpStatus = httpStatus;
            this.responseBody = responseBody == null ? "" : responseBody;
            this.vendorOk = vendorOk;
        }

        public int getHttpStatus() {
            return httpStatus;
        }

        public String getResponseBody() {
            return responseBody;
        }

        public boolean isVendorOk() {
            return vendorOk;
        }

        public void assertOk() {
            if (httpStatus >= 400) {
                throw new IllegalStateException("Webhook HTTP failed, status=" + httpStatus + " body=" + responseBody);
            }
            if (!vendorOk) {
                throw new IllegalStateException("Webhook vendor errcode, status=" + httpStatus + " body=" + responseBody);
            }
        }
    }
}
