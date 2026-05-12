package com.zephyr.watch.flink.process;

import com.zephyr.watch.common.constants.JobConfig;
import com.zephyr.watch.common.entity.AlertEvent;
import com.zephyr.watch.common.entity.RiskPrediction;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.flink.util.Collector;

public class FeatureAnomalyAlertFunction implements FlatMapFunction<RiskPrediction, AlertEvent> {

    @Override
    public void flatMap(RiskPrediction value, Collector<AlertEvent> out) {
        List<String> triggeredAnomalies = new ArrayList<String>();

        if (isTemperatureRising(value)) {
            triggeredAnomalies.add("TEMPERATURE_RISING");
            out.collect(buildAlert(
                value,
                "TEMPERATURE_RISING",
                "MEDIUM",
                String.format(
                    Locale.US,
                    "设备 %d 出现温度持续上升风险，temperatureTrend=%.4f，trendThreshold=%.4f，temperatureAvg=%.2f，avgThreshold=%.2f，riskProbability=%.4f。",
                    value.getMachineId(),
                    defaultDouble(value.getTemperatureTrend()),
                    JobConfig.FEATURE_ALERT_TEMPERATURE_TREND_THRESHOLD,
                    defaultDouble(value.getTemperatureAvg()),
                    JobConfig.FEATURE_ALERT_TEMPERATURE_AVG_THRESHOLD,
                    defaultDouble(value.getRiskProbability())
                )
            ));
        }

        if (defaultDouble(value.getSpeedStd()) >= JobConfig.FEATURE_ALERT_SPEED_STD_THRESHOLD) {
            triggeredAnomalies.add("SPEED_FLUCTUATION");
            out.collect(buildAlert(
                value,
                "SPEED_FLUCTUATION",
                "MEDIUM",
                String.format(
                    Locale.US,
                    "设备 %d 出现转速波动异常，speedStd=%.4f，threshold=%.4f，riskProbability=%.4f。",
                    value.getMachineId(),
                    defaultDouble(value.getSpeedStd()),
                    JobConfig.FEATURE_ALERT_SPEED_STD_THRESHOLD,
                    defaultDouble(value.getRiskProbability())
                )
            ));
        }

        if (defaultDouble(value.getPressureStd()) >= JobConfig.FEATURE_ALERT_PRESSURE_STD_THRESHOLD) {
            triggeredAnomalies.add("PRESSURE_FLUCTUATION");
            out.collect(buildAlert(
                value,
                "PRESSURE_FLUCTUATION",
                "MEDIUM",
                String.format(
                    Locale.US,
                    "设备 %d 出现压力波动异常，pressureStd=%.4f，threshold=%.4f，riskProbability=%.4f。",
                    value.getMachineId(),
                    defaultDouble(value.getPressureStd()),
                    JobConfig.FEATURE_ALERT_PRESSURE_STD_THRESHOLD,
                    defaultDouble(value.getRiskProbability())
                )
            ));
        }

        if (value.getRiskLabel() != null && value.getRiskLabel() == 1 && !triggeredAnomalies.isEmpty()) {
            out.collect(buildAlert(
                value,
                "COMPOSITE_CRITICAL_RISK",
                "CRITICAL",
                String.format(
                    Locale.US,
                    "设备 %d 命中复合高危告警，riskLabel=1，异常规则=%s，riskProbability=%.4f。",
                    value.getMachineId(),
                    String.join(",", triggeredAnomalies),
                    defaultDouble(value.getRiskProbability())
                )
            ));
        }
    }

    private boolean isTemperatureRising(RiskPrediction value) {
        return defaultDouble(value.getTemperatureTrend()) > JobConfig.FEATURE_ALERT_TEMPERATURE_TREND_THRESHOLD
            && defaultDouble(value.getTemperatureAvg()) >= JobConfig.FEATURE_ALERT_TEMPERATURE_AVG_THRESHOLD;
    }

    private AlertEvent buildAlert(RiskPrediction value, String alertType, String riskLevel, String message) {
        String alertId = String.format(
            Locale.US,
            "feature-%s-%d-%d",
            alertType.toLowerCase(Locale.ROOT),
            value.getMachineId(),
            value.getWindowEnd()
        );
        return new AlertEvent(
            alertId,
            value.getMachineId(),
            value.getWindowEnd(),
            value.getRiskProbability(),
            value.getRul(),
            riskLevel,
            alertType,
            message,
            "ONLINE_INFERENCE",
            "OPEN",
            value.getModelVersion()
        );
    }

    private double defaultDouble(Double value) {
        return value == null ? 0.0D : value;
    }
}
