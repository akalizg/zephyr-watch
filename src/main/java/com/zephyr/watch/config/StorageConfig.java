package com.zephyr.watch.config;

public final class StorageConfig {

    public static final String HDFS_ROOT = "hdfs://192.168.88.161:8020/zephyr";

    // DWD：清洗后的明细数据
    public static final String DWD_SENSOR_CLEAN_PATH = HDFS_ROOT + "/dwd/sensor_clean/";

    // DWS：窗口特征数据
    public static final String DWS_DEVICE_FEATURE_PATH = HDFS_ROOT + "/dws/device_feature/";

    public static final String OUTPUT_CHARSET = "UTF-8";

    private StorageConfig() {
    }
}