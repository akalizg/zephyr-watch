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
                plan.action,
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
                    "模型判定设备高风险，建议全面检查设备核心部件，安排检修。",
                    "核心部件检查包,轴承,润滑油,温度传感器"
            );
        }
        if ("TEMPERATURE_RISING".equals(alertType)) {
            return new RecommendationPlan(
                    "温度持续上升，建议检查冷却系统、润滑系统、温度传感器。",
                    "冷却风扇,润滑油,油滤,温度传感器"
            );
        }
        if ("SPEED_FLUCTUATION".equals(alertType)) {
            return new RecommendationPlan(
                    "转速波动异常，建议检查转轴、轴承、负载稳定性。",
                    "转轴检查包,轴承,联轴器,负载校准工具"
            );
        }
        if ("PRESSURE_FLUCTUATION".equals(alertType)) {
            return new RecommendationPlan(
                    "压力波动异常，建议检查气路、压力阀、密封性。",
                    "压力阀,密封圈,气路接头,压力传感器"
            );
        }
        if ("COMPOSITE_CRITICAL_RISK".equals(alertType)) {
            return new RecommendationPlan(
                    "模型高风险叠加实时特征异常，建议立即停机检查，生成 P0 工单。",
                    "应急检修包,轴承,密封圈,冷却风扇,压力阀"
            );
        }
        if ("CEP_CONSECUTIVE_HIGH_RISK".equals(alertType)) {
            return new RecommendationPlan(
                    "连续高风险窗口命中，建议复核连续高风险窗口，安排人工确认。",
                    "人工复核清单,标准巡检包"
            );
        }
        return new RecommendationPlan(
                "设备存在风险告警，建议结合实时特征、RUL 和历史相似案例安排预防性检修。",
                "标准巡检包,常用备件包"
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
        double riskScore = defaultDouble(alert.getRiskProbability(), 0.0D);
        double rulUrgency = 1.0D - Math.min(defaultDouble(alert.getRul(), 120.0D) / 120.0D, 1.0D);
        double similarity = 1.0D / (1.0D + distance(alert, candidate));
        double score = riskScore * 0.5D + rulUrgency * 0.3D + similarity * 0.2D;
        return Math.round(score * 10000.0D) / 10000.0D;
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
        return value == null ? "" : value.toUpperCase();
    }

    private static final class RecommendationPlan {
        private final String action;
        private final String spareParts;

        private RecommendationPlan(String action, String spareParts) {
            this.action = action;
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
