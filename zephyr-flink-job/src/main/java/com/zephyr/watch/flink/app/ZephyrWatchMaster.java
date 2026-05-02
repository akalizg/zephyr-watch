package com.zephyr.watch.flink.app;

/**
 * Compatibility entry point that delegates to the online inference job.
 */
public class ZephyrWatchMaster {

    public static void main(String[] args) throws Exception {
        OnlineInferenceJob.main(args);
    }
}
