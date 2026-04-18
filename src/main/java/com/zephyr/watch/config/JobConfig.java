package com.zephyr.watch.config;

public final class JobConfig {

    public static final int PARALLELISM = 1;
    public static final long CHECKPOINT_INTERVAL_MS = 10_000L;
    public static final int WINDOW_SECONDS = 5;

    public static final String OFFLINE_JOB_NAME = "Zephyr-Watch Offline Feature Job";
    public static final String LOCAL_DEBUG_JOB_NAME = "Zephyr-Watch Local Debug Job";

    public static final String DATA_FILE_PATH = "data/train_FD001.txt";
    public static final long PRODUCER_SLEEP_MS = 500L;

    private JobConfig() {
    }
}