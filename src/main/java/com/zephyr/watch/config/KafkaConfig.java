package com.zephyr.watch.config;

public final class KafkaConfig {

    public static final String BOOTSTRAP_SERVERS = "192.168.88.161:9092";
    public static final String INPUT_TOPIC = "iot_sensor_data";
    public static final String GROUP_ID = "zephyr-ai-test-group-001";

    private KafkaConfig() {
    }
}