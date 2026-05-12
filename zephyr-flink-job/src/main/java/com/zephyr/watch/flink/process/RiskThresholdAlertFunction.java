package com.zephyr.watch.flink.process;

import com.zephyr.watch.common.entity.AlertEvent;
import com.zephyr.watch.common.entity.RiskPrediction;
import java.util.Locale;
import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.flink.util.Collector;

public class RiskThresholdAlertFunction implements FlatMapFunction<RiskPrediction, AlertEvent> {

    @Override
    public void flatMap(RiskPrediction value, Collector<AlertEvent> out) {
        if (value.getRiskLabel() == null || value.getRiskLabel() == 0) {
            return;
        }

        String alertId = String.format(
            Locale.US,
            "threshold-%d-%d",
            value.getMachineId(),
            value.getWindowEnd()
        );
        String message = String.format(
            Locale.US,
            "Device %d risk=%s probability=%.4f rul=%.2f",
            value.getMachineId(),
            value.getRiskLevel(),
            value.getRiskProbability(),
            value.getRul()
        );

        out.collect(new AlertEvent(
            alertId,
            value.getMachineId(),
            value.getWindowEnd(),
            value.getRiskProbability(),
            value.getRul(),
            value.getRiskLevel(),
            "RISK_THRESHOLD",
            message,
            "ONLINE_INFERENCE",
            "OPEN",
            value.getModelVersion()
        ));
    }
}
