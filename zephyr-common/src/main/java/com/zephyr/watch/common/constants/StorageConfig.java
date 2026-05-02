package com.zephyr.watch.common.constants;

public final class StorageConfig {

    public static final String HDFS_ROOT = "hdfs://192.168.88.161:8020/zephyr";

    // DWD锛氭竻娲楀悗鐨勬槑缁嗘暟鎹?
    public static final String DWD_SENSOR_CLEAN_PATH = HDFS_ROOT + "/dwd/sensor_clean/";

    // DWS锛氱獥鍙ｇ壒寰佹暟鎹?
    public static final String DWS_DEVICE_FEATURE_PATH = HDFS_ROOT + "/dws/device_feature/";

    public static final String OUTPUT_CHARSET = "UTF-8";

    public static final String MYSQL_URL = "jdbc:mysql://127.0.0.1:3306/zephyr_watch?useUnicode=true&characterEncoding=utf8&useSSL=false";
    public static final String MYSQL_USER = "root";
    public static final String MYSQL_PASSWORD = "nisibusisa250";

    public static final String REDIS_HOST = "127.0.0.1";
    public static final int REDIS_PORT = 6379;

    public static final String ALERT_WEBHOOK_URL = "";

    private StorageConfig() {
    }
}

