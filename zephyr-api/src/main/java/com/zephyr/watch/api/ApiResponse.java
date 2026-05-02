package com.zephyr.watch.api;

import java.util.HashMap;
import java.util.Map;

public final class ApiResponse {

    private ApiResponse() {
    }

    public static Map<String, Object> ok(String module, String message) {
        Map<String, Object> result = new HashMap<String, Object>();
        result.put("module", module);
        result.put("message", message);
        result.put("status", "READY");
        return result;
    }
}
