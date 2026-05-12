package com.zephyr.watch.common.constants;

public final class StorageConfig {

    // 1. 基础根路径保持不变
    public static final String HDFS_ROOT = env("ZEPHYR_HDFS_ROOT", "hdfs://192.168.88.161:8020/zephyr");

    // 2. 将路径改回原来的 features 目录
    public static final String DWD_SENSOR_CLEAN_PATH = HDFS_ROOT + "/features/";

    // 3. 特征数据也存放在这里，或者你可以根据需要指定
    public static final String DWS_DEVICE_FEATURE_PATH = HDFS_ROOT + "/features/";

    public static final String OUTPUT_CHARSET = "UTF-8";

    // 保持 MySQL 连接到虚拟机
    public static final String MYSQL_URL = env("ZEPHYR_MYSQL_URL", "jdbc:mysql://127.0.0.1/zephyr_watch?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai");
    public static final String MYSQL_USER = env("ZEPHYR_MYSQL_USER", "root");
    public static final String MYSQL_PASSWORD = env("ZEPHYR_MYSQL_PASSWORD", "comeonYZ6");

    // 保持 Redis 连接到虚拟机
    public static final String REDIS_HOST = env("ZEPHYR_REDIS_HOST", "127.2.0.1");
    public static final int REDIS_PORT = Integer.parseInt(env("ZEPHYR_REDIS_PORT", "6379"));

    public static final String ALERT_WEBHOOK_URL = env("ZEPHYR_ALERT_WEBHOOK_URL", "");

    private StorageConfig() {
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.trim().isEmpty() ? defaultValue : value.trim();
    }
}