package com.zephyr.watch.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public class ApiDocsLiteController {

    @GetMapping("/api/docs-lite")
    public Map<String, Object> docsLite() {
        List<Map<String, Object>> groups = new ArrayList<Map<String, Object>>();
        groups.add(group("Dashboard",
                item("GET", "/api/dashboard/overview", "系统业务总览"),
                item("GET", "/api/dashboard/risk-level-distribution", "风险等级分布"),
                item("GET", "/api/dashboard/alert-type-distribution", "告警类型分布"),
                item("GET", "/api/dashboard/top-risk-machines", "设备风险 TopN"),
                item("GET", "/api/dashboard/latest-predictions", "最新预测记录"),
                item("GET", "/api/dashboard/latest-alerts", "最新告警记录"),
                item("GET", "/api/dashboard/latest-recommendations", "最新维修推荐")
        ));
        groups.add(group("Models",
                item("GET", "/api/models", "模型注册列表"),
                item("GET", "/api/models/active", "当前激活模型"),
                item("POST", "/api/models", "注册模型元数据"),
                item("POST", "/api/models/{modelVersion}/activate", "激活指定模型")
        ));
        groups.add(group("Review",
                item("GET", "/api/alerts", "告警列表"),
                item("POST", "/api/alerts/review", "提交人工审核"),
                item("GET", "/api/learning/review-labels", "导出审核标签"),
                item("GET", "/api/learning/feedback-samples", "导出反馈训练样本"),
                item("GET", "/api/learning/feedback-training-samples", "导出反馈训练样本")
        ));
        groups.add(group("Recommendations",
                item("GET", "/api/recommendations", "维修推荐查询")
        ));
        groups.add(group("Risks",
                item("GET", "/api/risks/{machineId}", "设备最新风险"),
                item("GET", "/api/risks/{machineId}/history", "设备风险历史")
        ));
        return ApiResponse.ok("docs-lite", groups);
    }

    private Map<String, Object> group(String name, Map<String, Object>... items) {
        Map<String, Object> group = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> itemList = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> item : items) {
            itemList.add(item);
        }
        group.put("group", name);
        group.put("items", itemList);
        return group;
    }

    private Map<String, Object> item(String method, String path, String description) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("method", method);
        item.put("path", path);
        item.put("description", description);
        return item;
    }
}
