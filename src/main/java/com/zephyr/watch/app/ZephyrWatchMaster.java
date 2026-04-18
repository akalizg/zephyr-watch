package com.zephyr.watch.app;

/**
 * 兼容旧启动入口
 */
public class ZephyrWatchMaster {

    public static void main(String[] args) throws Exception {
        OfflineFeatureJob.main(args);
    }
}