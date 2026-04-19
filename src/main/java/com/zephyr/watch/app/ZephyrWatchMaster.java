package com.zephyr.watch.app;

/**
 * 兼容旧入口，统一转到离线数仓作业
 */
public class ZephyrWatchMaster {

    public static void main(String[] args) throws Exception {
        OfflineWarehouseJob.main(args);
    }
}