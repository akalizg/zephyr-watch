package com.zephyr.watch.common.constants;

public final class KafkaConfig {

    public static final String BOOTSTRAP_SERVERS = "192.168.88.161:9092";
    public static final String INPUT_TOPIC = "iot_sensor_data";
    public static final String RISK_PREDICTION_TOPIC = "risk_prediction_topic";
    public static final String ALERT_EVENT_TOPIC = "alert_event_topic";
    public static final String INVALID_SENSOR_TOPIC = "invalid_sensor_topic";
    public static final String GROUP_ID = "zephyr-ai-test-group-001";
    public static final String ONLINE_INFERENCE_GROUP_ID = "zephyr-online-inference-group";
    public static final String ALERT_TRANSACTIONAL_ID_PREFIX = "zephyr-alert-tx";
    public static final String RISK_TRANSACTIONAL_ID_PREFIX = "zephyr-risk-tx";

    private KafkaConfig() {
    }
}

