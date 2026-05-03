package com.zephyr.watch.flink.process;

import com.zephyr.watch.common.constants.JobConfig;
import com.zephyr.watch.common.entity.FeatureVector;
import com.zephyr.watch.common.entity.RiskPrediction;
import org.apache.flink.api.common.functions.RichMapFunction;
import org.apache.flink.configuration.Configuration;
import org.jpmml.evaluator.Evaluator;
import org.jpmml.evaluator.EvaluatorUtil;
import org.jpmml.evaluator.InputField;
import org.jpmml.evaluator.LoadingModelEvaluatorBuilder;
import org.jpmml.evaluator.TargetField;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class OnlineRiskPredictFunction extends RichMapFunction<FeatureVector, RiskPrediction> {

    private final String pmmlFilePath;
    private final String modelVersion;
    private transient Evaluator evaluator;

    public OnlineRiskPredictFunction(String pmmlFilePath, String modelVersion) {
        this.pmmlFilePath = pmmlFilePath;
        this.modelVersion = modelVersion;
    }

    @Override
    public void open(Configuration parameters) throws Exception {
        File pmmlFile = resolvePmmlFile();
        evaluator = new LoadingModelEvaluatorBuilder()
                .load(pmmlFile)
                .build();
        evaluator.verify();
    }

    private File resolvePmmlFile() {
        File configuredPath = new File(pmmlFilePath);
        if (configuredPath.exists()) {
            return configuredPath;
        }

        File modulePath = new File("zephyr-flink-job", pmmlFilePath);
        if (modulePath.exists()) {
            return modulePath;
        }

        throw new IllegalArgumentException("PMML model file not found: " + configuredPath.getPath()
                + " or " + modulePath.getPath());
    }

    @Override
    public RiskPrediction map(FeatureVector fv) {
        Double rul = predictRul(fv);
        double riskProbability = riskProbability(fv, rul);
        int riskLabel = riskProbability >= JobConfig.RISK_ALERT_THRESHOLD ? 1 : 0;
        String riskLevel = riskLevel(riskProbability, rul);
        String predictionId = String.format(
                Locale.US,
                "%d-%d-%s",
                fv.getMachineId(),
                fv.getWindowEnd(),
                modelVersion
        );

        RiskPrediction prediction = new RiskPrediction(
                predictionId,
                fv.getMachineId(),
                fv.getWindowStart(),
                fv.getWindowEnd(),
                fv.getCycleStart(),
                fv.getCycleEnd(),
                rul,
                riskProbability,
                riskLabel,
                riskLevel,
                modelVersion,
                System.currentTimeMillis()
        );
        copyFeatures(fv, prediction);
        return prediction;
    }

    private void copyFeatures(FeatureVector fv, RiskPrediction prediction) {
        prediction.setSampleCount(fv.getSampleCount());
        prediction.setPressureMin(fv.getPressureMin());
        prediction.setPressureMax(fv.getPressureMax());
        prediction.setPressureAvg(fv.getPressureAvg());
        prediction.setPressureStd(fv.getPressureStd());
        prediction.setPressureTrend(fv.getPressureTrend());
        prediction.setTemperatureMin(fv.getTemperatureMin());
        prediction.setTemperatureMax(fv.getTemperatureMax());
        prediction.setTemperatureAvg(fv.getTemperatureAvg());
        prediction.setTemperatureStd(fv.getTemperatureStd());
        prediction.setTemperatureTrend(fv.getTemperatureTrend());
        prediction.setSpeedMin(fv.getSpeedMin());
        prediction.setSpeedMax(fv.getSpeedMax());
        prediction.setSpeedAvg(fv.getSpeedAvg());
        prediction.setSpeedStd(fv.getSpeedStd());
        prediction.setSpeedTrend(fv.getSpeedTrend());
    }

    private Double predictRul(FeatureVector fv) {
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        List<? extends InputField> inputFields = evaluator.getInputFields();
        for (InputField inputField : inputFields) {
            String name = inputField.getName();
            arguments.put(name, extractValueFromFeatureVector(fv, name));
        }

        Map<String, ?> results = evaluator.evaluate(arguments);
        TargetField targetField = evaluator.getTargetFields().get(0);
        Object targetValue = results.get(targetField.getName());
        Object decoded = EvaluatorUtil.decode(targetValue);
        if (decoded instanceof Number) {
            return ((Number) decoded).doubleValue();
        }
        return Double.valueOf(String.valueOf(decoded));
    }

    private double riskProbability(FeatureVector fv, double rul) {
        double rulSignal = 1.0D / (1.0D + Math.exp((rul - JobConfig.RUL_WARNING_THRESHOLD) / 12.0D));
        double volatilitySignal = normalize(fv.getPressureStd(), 0.0D, 8.0D) * 0.30D
                + normalize(fv.getTemperatureStd(), 0.0D, 8.0D) * 0.30D
                + normalize(Math.abs(fv.getSpeedTrend()), 0.0D, 25.0D) * 0.40D;
        double probability = rulSignal * 0.85D + volatilitySignal * 0.15D;
        return Math.max(0.0D, Math.min(1.0D, probability));
    }

    private double normalize(double value, double min, double max) {
        if (max <= min) {
            return 0.0D;
        }
        return Math.max(0.0D, Math.min(1.0D, (value - min) / (max - min)));
    }

    private String riskLevel(double riskProbability, double rul) {
        if (riskProbability >= JobConfig.RISK_CRITICAL_THRESHOLD || rul <= JobConfig.RUL_CRITICAL_THRESHOLD) {
            return "CRITICAL";
        }
        if (riskProbability >= JobConfig.RISK_ALERT_THRESHOLD || rul <= JobConfig.RUL_WARNING_THRESHOLD) {
            return "HIGH";
        }
        if (riskProbability >= 0.45D) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private Object extractValueFromFeatureVector(FeatureVector fv, String fieldName) {
        switch (fieldName) {
            case "pressureMin": return fv.getPressureMin();
            case "pressureMax": return fv.getPressureMax();
            case "pressureAvg": return fv.getPressureAvg();
            case "pressureStd": return fv.getPressureStd();
            case "pressureTrend": return fv.getPressureTrend();
            case "temperatureMin": return fv.getTemperatureMin();
            case "temperatureMax": return fv.getTemperatureMax();
            case "temperatureAvg": return fv.getTemperatureAvg();
            case "temperatureStd": return fv.getTemperatureStd();
            case "temperatureTrend": return fv.getTemperatureTrend();
            case "speedMin": return fv.getSpeedMin();
            case "speedMax": return fv.getSpeedMax();
            case "speedAvg": return fv.getSpeedAvg();
            case "speedStd": return fv.getSpeedStd();
            case "speedTrend": return fv.getSpeedTrend();
            default: return 0.0D;
        }
    }
}

