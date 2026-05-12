package com.zephyr.watch.flink.process;

import com.zephyr.watch.common.constants.StorageConfig;
import com.zephyr.watch.common.entity.AlertEvent;
import com.zephyr.watch.common.entity.MaintenanceRecommendation;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.apache.flink.api.common.functions.RichMapFunction;
import org.apache.flink.configuration.Configuration;

public class MaintenanceRecommendationFunction extends RichMapFunction<AlertEvent, MaintenanceRecommendation> {

    private static final List<CaseProfile> DEFAULT_CASES = Arrays.asList(
            new CaseProfile("CASE-CRITICAL-BEARING", 0.95D, 20.0D, "CRITICAL", "COMPOSITE_CRITICAL_RISK"),
            new CaseProfile("CASE-HIGH-MODEL-RISK", 0.82D, 45.0D, "HIGH", "MODEL_HIGH_RISK"),
            new CaseProfile("CASE-MEDIUM-THERMAL", 0.68D, 80.0D, "MEDIUM", "TEMPERATURE_RISING"),
            new CaseProfile("CASE-MEDIUM-SPEED", 0.62D, 90.0D, "MEDIUM", "SPEED_FLUCTUATION"),
            new CaseProfile("CASE-MEDIUM-PRESSURE", 0.60D, 95.0D, "MEDIUM", "PRESSURE_FLUCTUATION")
    );

    private transient List<CaseProfile> caseProfiles;

    @Override
    public void open(Configuration parameters) {
        caseProfiles = loadHistoricalCases();
        if (caseProfiles.isEmpty()) {
            caseProfiles = DEFAULT_CASES;
        }
    }

    @Override
    public MaintenanceRecommendation map(AlertEvent alert) {
        CaseProfile best = findNearestCase(alert);
        RecommendationPlan plan = planFor(alert);
        double score = recommendationScore(alert, best);
        return new MaintenanceRecommendation(
                null,
                alert.getAlertId(),
                alert.getMachineId(),
                buildAction(alert, plan),
                plan.spareParts,
                priority(alert.getRiskLevel()),
                best.caseId,
                score
        );
    }

    private RecommendationPlan planFor(AlertEvent alert) {
        String alertType = safeUpper(alert.getAlertType());
        if ("MODEL_HIGH_RISK".equals(alertType) || "RISK_THRESHOLD".equals(alertType)) {
            return new RecommendationPlan(
                    "模型高风险",
                    "模型判定设备风险较高",
                    "全面检查设备核心部件并安排检修",
                    "模型风险概率、RUL 紧急程度和历史相似案例共同评估。",
                    "核心部件检查包,轴承,润滑油,温度传感器"
            );
        }
        if ("TEMPERATURE_RISING".equals(alertType)) {
            return new RecommendationPlan(
                    "温度上升",
                    "实时特征显示温度均值较高且趋势上升",
                    "检查冷却系统、润滑系统和温度传感器",
                    "温度趋势异常叠加模型风险和相似热故障案例。",
                    "冷却风扇,润滑油,油滤,温度传感器"
            );
        }
        if ("SPEED_FLUCTUATION".equals(alertType)) {
            return new RecommendationPlan(
                    "转速波动",
                    "实时特征显示转速标准差超过阈值",
                    "检查转轴、轴承和负载稳定性",
                    "转速波动、RUL 紧急程度和历史旋转部件案例共同触发。",
                    "转轴检查包,轴承,联轴器,负载校准工具"
            );
        }
        if ("PRESSURE_FLUCTUATION".equals(alertType)) {
            return new RecommendationPlan(
                    "压力波动",
                    "实时特征显示压力标准差超过阈值",
                    "检查气路、压力阀和密封性",
                    "压力波动、模型风险和相似气路故障案例共同触发。",
                    "压力阀,密封圈,气路接头,压力传感器"
            );
        }
        if ("COMPOSITE_CRITICAL_RISK".equals(alertType)) {
            return new RecommendationPlan(
                    "复合高危",
                    "设备同时命中模型高风险和实时特征异常",
                    "立即停机检查并生成 P0 工单",
                    "模型风险、RUL 紧急程度和历史相似案例共同指向高危状态。",
                    "应急检修包,轴承,密封圈,冷却风扇,压力阀"
            );
        }
        if ("CEP_CONSECUTIVE_HIGH_RISK".equals(alertType)) {
            return new RecommendationPlan(
                    "连续高风险",
                    "CEP 检测到连续高风险窗口",
                    "复核连续高风险窗口并安排人工确认",
                    "连续窗口风险、模型输出和人工复核价值共同触发。",
                    "人工复核清单,标准巡检包"
            );
        }
        return new RecommendationPlan(
                "通用风险告警",
                "设备存在待处理风险告警",
                "结合实时特征和历史案例安排预防性检修",
                "模型风险、RUL 和历史相似案例综合评估。",
                "标准巡检包,常用备件包"
        );
    }

    private String buildAction(AlertEvent alert, RecommendationPlan plan) {
        return String.format(
                Locale.US,
                "【%s】%s，riskLevel=%s，riskProbability=%.4f，RUL=%.2f，建议%s。推荐依据：%s",
                plan.title,
                plan.meaning,
                valueOrUnknown(alert.getRiskLevel()),
                defaultDouble(alert.getRiskProbability(), 0.0D),
                defaultDouble(alert.getRul(), -1.0D),
                plan.action,
                plan.basis
        );
    }

    private CaseProfile findNearestCase(AlertEvent alert) {
        CaseProfile best = caseProfiles.get(0);
        double bestDistance = Double.MAX_VALUE;
        for (CaseProfile candidate : caseProfiles) {
            double distance = distance(alert, candidate);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    private double distance(AlertEvent alert, CaseProfile candidate) {
        double risk = defaultDouble(alert.getRiskProbability(), 0.0D);
        double rul = defaultDouble(alert.getRul(), 999.0D);
        double levelPenalty = candidate.riskLevel.equalsIgnoreCase(String.valueOf(alert.getRiskLevel())) ? 0.0D : 0.25D;
        double typePenalty = candidate.alertType.equalsIgnoreCase(String.valueOf(alert.getAlertType())) ? 0.0D : 0.2D;
        return Math.abs(risk - candidate.riskProbability)
                + Math.min(Math.abs(rul - candidate.rul) / 120.0D, 1.0D)
                + levelPenalty
                + typePenalty;
    }

    private double recommendationScore(AlertEvent alert, CaseProfile candidate) {
        double score = riskScore(alert) * 0.5D
                + rulUrgencyScore(alert) * 0.3D
                + similarCaseScore(alert, candidate) * 0.2D;
        return Math.round(score * 10000.0D) / 10000.0D;
    }

    private double riskScore(AlertEvent alert) {
        return Math.max(0.0D, Math.min(defaultDouble(alert.getRiskProbability(), 0.0D), 1.0D));
    }

    private double rulUrgencyScore(AlertEvent alert) {
        double rul = defaultDouble(alert.getRul(), 120.0D);
        return 1.0D - Math.min(Math.max(rul, 0.0D) / 120.0D, 1.0D);
    }

    private double similarCaseScore(AlertEvent alert, CaseProfile candidate) {
        return 1.0D / (1.0D + distance(alert, candidate));
    }

    private String priority(String riskLevel) {
        if ("CRITICAL".equalsIgnoreCase(riskLevel)) {
            return "P0";
        }
        if ("HIGH".equalsIgnoreCase(riskLevel)) {
            return "P1";
        }
        if ("MEDIUM".equalsIgnoreCase(riskLevel)) {
            return "P2";
        }
        return "P3";
    }

    private List<CaseProfile> loadHistoricalCases() {
        List<CaseProfile> cases = new ArrayList<CaseProfile>();
        String sql = "SELECT ae.alert_id, ae.risk_probability, ae.rul, ae.risk_level, ae.alert_type "
                + "FROM maintenance_recommendation mr "
                + "JOIN alert_event ae ON mr.alert_id = ae.alert_id "
                + "ORDER BY mr.created_at DESC LIMIT 200";

        Connection connection = null;
        Statement statement = null;
        ResultSet resultSet = null;
        try {
            Class.forName("com.mysql.jdbc.Driver");
            connection = DriverManager.getConnection(
                    StorageConfig.MYSQL_URL,
                    StorageConfig.MYSQL_USER,
                    StorageConfig.MYSQL_PASSWORD
            );
            statement = connection.createStatement();
            resultSet = statement.executeQuery(sql);
            while (resultSet.next()) {
                cases.add(new CaseProfile(
                        resultSet.getString("alert_id"),
                        resultSet.getDouble("risk_probability"),
                        resultSet.getDouble("rul"),
                        resultSet.getString("risk_level"),
                        resultSet.getString("alert_type")
                ));
            }
        } catch (Exception ignored) {
            return cases;
        } finally {
            closeQuietly(resultSet);
            closeQuietly(statement);
            closeQuietly(connection);
        }
        return cases;
    }

    private void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Keep recommendation streaming available when the case library is temporarily unavailable.
        }
    }

    private double defaultDouble(Double value, double defaultValue) {
        return value == null ? defaultValue : value;
    }

    private String safeUpper(String value) {
        return value == null ? "" : value.toUpperCase(Locale.ROOT);
    }

    private String valueOrUnknown(String value) {
        return value == null || value.trim().isEmpty() ? "UNKNOWN" : value;
    }

    private static final class RecommendationPlan {
        private final String title;
        private final String meaning;
        private final String action;
        private final String basis;
        private final String spareParts;

        private RecommendationPlan(String title, String meaning, String action, String basis, String spareParts) {
            this.title = title;
            this.meaning = meaning;
            this.action = action;
            this.basis = basis;
            this.spareParts = spareParts;
        }
    }

    private static final class CaseProfile {
        private final String caseId;
        private final double riskProbability;
        private final double rul;
        private final String riskLevel;
        private final String alertType;

        private CaseProfile(String caseId, double riskProbability, double rul, String riskLevel, String alertType) {
            this.caseId = caseId;
            this.riskProbability = riskProbability;
            this.rul = rul;
            this.riskLevel = riskLevel == null ? "" : riskLevel;
            this.alertType = alertType == null ? "" : alertType;
        }
    }
}
