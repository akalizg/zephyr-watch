package com.zephyr.watch.flink.process;

import com.zephyr.watch.common.constants.StorageConfig;
import com.zephyr.watch.common.entity.AlertEvent;
import com.zephyr.watch.common.entity.MaintenanceRecommendation;
import org.apache.flink.api.common.functions.RichMapFunction;
import org.apache.flink.configuration.Configuration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MaintenanceRecommendationFunction extends RichMapFunction<AlertEvent, MaintenanceRecommendation> {

    private static final List<CaseProfile> DEFAULT_CASES = Arrays.asList(
            new CaseProfile("CASE-CRITICAL-BEARING", 0.95D, 20.0D, "CRITICAL",
                    "Stop machine and inspect bearing, compressor and lubricant circuit",
                    "bearing kit,lubricant,temperature sensor", "P0"),
            new CaseProfile("CASE-HIGH-THERMAL", 0.82D, 45.0D, "HIGH",
                    "Schedule same-day thermal inspection and reduce load before restart",
                    "cooling fan,oil filter,thermal paste", "P1"),
            new CaseProfile("CASE-MEDIUM-PREVENTIVE", 0.62D, 80.0D, "MEDIUM",
                    "Create preventive maintenance ticket and re-check trend after next cycle",
                    "standard inspection kit", "P2")
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
        double score = similarityScore(alert, best);
        return new MaintenanceRecommendation(
                null,
                alert.getAlertId(),
                alert.getMachineId(),
                best.action,
                best.spareParts,
                priority(alert, best.priority),
                best.caseId,
                score
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
        double risk = alert.getRiskProbability() == null ? 0.0D : alert.getRiskProbability();
        double rul = alert.getRul() == null ? 999.0D : alert.getRul();
        double levelPenalty = candidate.riskLevel.equalsIgnoreCase(String.valueOf(alert.getRiskLevel())) ? 0.0D : 0.25D;
        return Math.abs(risk - candidate.riskProbability) + Math.min(Math.abs(rul - candidate.rul) / 120.0D, 1.0D) + levelPenalty;
    }

    private double similarityScore(AlertEvent alert, CaseProfile candidate) {
        double score = 1.0D / (1.0D + distance(alert, candidate));
        return Math.round(score * 10000.0D) / 10000.0D;
    }

    private String priority(AlertEvent alert, String defaultPriority) {
        if ("CRITICAL".equalsIgnoreCase(alert.getRiskLevel())) {
            return "P0";
        }
        if ("HIGH".equalsIgnoreCase(alert.getRiskLevel())) {
            return "P1";
        }
        return defaultPriority;
    }

    private List<CaseProfile> loadHistoricalCases() {
        List<CaseProfile> cases = new ArrayList<CaseProfile>();
        String sql = "SELECT ae.alert_id, ae.risk_probability, ae.rul, ae.risk_level, "
                + "mr.action, mr.spare_parts, mr.work_order_priority "
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
                        resultSet.getString("action"),
                        resultSet.getString("spare_parts"),
                        resultSet.getString("work_order_priority")
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

    private static final class CaseProfile {
        private final String caseId;
        private final double riskProbability;
        private final double rul;
        private final String riskLevel;
        private final String action;
        private final String spareParts;
        private final String priority;

        private CaseProfile(String caseId, double riskProbability, double rul, String riskLevel,
                            String action, String spareParts, String priority) {
            this.caseId = caseId;
            this.riskProbability = riskProbability;
            this.rul = rul;
            this.riskLevel = riskLevel;
            this.action = action;
            this.spareParts = spareParts;
            this.priority = priority;
        }
    }
}
