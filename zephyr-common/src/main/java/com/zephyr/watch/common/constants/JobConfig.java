package com.zephyr.watch.common.constants;

public final class JobConfig {

    public static final int PARALLELISM = 1;
    public static final long CHECKPOINT_INTERVAL_MS = 10_000L;

    public static final int WINDOW_SECONDS = 5;
    public static final int FEATURE_WINDOW_SECONDS = 30;
    public static final int FEATURE_WINDOW_SLIDE_SECONDS = 5;
    public static final int WATERMARK_OUT_OF_ORDERNESS_SECONDS = 3;
    public static final int WATERMARK_IDLE_SECONDS = 30;

    public static final String OFFLINE_JOB_NAME = "Zephyr-Watch DWD+DWS Offline Job";
    public static final String LOCAL_DEBUG_JOB_NAME = "Zephyr-Watch Local Debug Job";
    public static final String ONLINE_INFERENCE_JOB_NAME = "Zephyr-Watch Online Inference Job";

    public static final String DATA_FILE_PATH = "data/train_FD001.txt";
    public static final long PRODUCER_SLEEP_MS = 500L;

    public static final String DEFAULT_PMML_MODEL_PATH = "src/main/resources/models/model.pmml";
    public static final String CHECKPOINT_STORAGE_PATH = "file:///D:/Javatest/zephyr-watch/target/flink-checkpoints";
    public static final boolean DEFAULT_DEBUG_STREAM_PRINT_ENABLED = false;

    public static final double RUL_CRITICAL_THRESHOLD = 30.0D;
    public static final double RUL_WARNING_THRESHOLD = 60.0D;
    public static final double RISK_ALERT_THRESHOLD = 0.70D;
    public static final double RISK_CRITICAL_THRESHOLD = 0.90D;
    public static final String DEFAULT_MODEL_VERSION = "pmml-local-rul-v1";

    private JobConfig() {
    }
}

