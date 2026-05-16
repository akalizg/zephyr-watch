package com.zephyr.watch.common.webhook;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * DingTalk and WeCom (企业微信) group robots use the same HMAC-SHA256 signing scheme for
 * {@code timestamp} / {@code sign} query parameters when "加签" is enabled.
 */
public final class WebhookUrlSigner {

    private WebhookUrlSigner() {
    }

    /**
     * Appends {@code timestamp} and {@code sign} to the webhook URL when {@code signSecret} is non-blank.
     * If {@code signSecret} is null or blank, returns {@code webhookUrl} unchanged.
     */
    public static String appendSignQuery(String webhookUrl, String signSecret) {
        if (isBlank(webhookUrl)) {
            return webhookUrl;
        }
        if (isBlank(signSecret)) {
            return webhookUrl;
        }
        String secret = signSecret.trim();
        long timestamp = System.currentTimeMillis();
        String sign = base64HmacSha256(secret, timestamp + "\n" + secret);
        final String encodedSign;
        try {
            // Java 8: URLEncoder.encode(String, Charset) is Java 10+
            encodedSign = URLEncoder.encode(sign, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 required for webhook sign URL encoding", e);
        }
        String sep = webhookUrl.contains("?") ? "&" : "?";
        return webhookUrl + sep + "timestamp=" + timestamp + "&sign=" + encodedSign;
    }

    static String base64HmacSha256(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] raw = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(raw);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("webhook sign failed", e);
        }
    }

    /** Java 8 compatible substitute for {@code String.isBlank()} (Java 11+). */
    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
