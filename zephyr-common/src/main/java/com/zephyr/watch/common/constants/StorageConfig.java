package com.zephyr.watch.common.constants;

public final class StorageConfig {

    public static final String HDFS_ROOT = env("ZEPHYR_HDFS_ROOT", "hdfs://127.0.0.1:8020/zephyr");

    public static final String DWD_SENSOR_CLEAN_PATH = HDFS_ROOT + "/dwd/sensor_clean/";

    public static final String DWS_DEVICE_FEATURE_PATH = HDFS_ROOT + "/dws/device_feature/";

    public static final String OUTPUT_CHARSET = "UTF-8";

    public static final String MYSQL_URL = env("ZEPHYR_MYSQL_URL", "jdbc:mysql://127.0.0.1:3306/zephyr_watch?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai");
    public static final String MYSQL_USER = env("ZEPHYR_MYSQL_USER", "root");
    public static final String MYSQL_PASSWORD = env("ZEPHYR_MYSQL_PASSWORD", "root");

    public static final String REDIS_HOST = env("ZEPHYR_REDIS_HOST", "127.0.0.1");
    public static final int REDIS_PORT = Integer.parseInt(env("ZEPHYR_REDIS_PORT", "6379"));

    public static final String ALERT_WEBHOOK_URL = env("ZEPHYR_ALERT_WEBHOOK_URL", "");

    private StorageConfig() {
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.trim().isEmpty() ? defaultValue : value.trim();
    }
}
