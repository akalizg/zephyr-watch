package com.zephyr.watch.flink.process;

import com.zephyr.watch.common.entity.AlertEvent;
import com.zephyr.watch.common.entity.RiskPrediction;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.flink.cep.PatternSelectFunction;

public class CepConsecutiveRiskAlertSelector implements PatternSelectFunction<RiskPrediction, AlertEvent> {

    @Override
    public AlertEvent select(Map<String, List<RiskPrediction>> pattern) {
        RiskPrediction first = pattern.get("first").get(0);
        RiskPrediction second = pattern.get("second").get(0);

        double maxRisk = Math.max(first.getRiskProbability(), second.getRiskProbability());
        double minRul = Math.min(first.getRul(), second.getRul());

        String alertId = String.format(
            Locale.US,
            "cep-consecutive-%d-%d",
            second.getMachineId(),
            second.getWindowEnd()
        );
        String message = String.format(
            Locale.US,
            "Device %d has consecutive high-risk windows, maxRisk=%.4f minRul=%.2f",
            second.getMachineId(),
            maxRisk,
            minRul
        );

        return new AlertEvent(
            alertId,
            second.getMachineId(),
            second.getWindowEnd(),
            maxRisk,
            minRul,
            second.getRiskLevel(),
            "CEP_CONSECUTIVE_HIGH_RISK",
            message,
            "FLINK_CEP",
            "OPEN",
            second.getModelVersion()
        );
    }
}
