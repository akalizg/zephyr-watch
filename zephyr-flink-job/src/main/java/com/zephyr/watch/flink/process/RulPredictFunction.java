package com.zephyr.watch.flink.process;

import com.zephyr.watch.common.entity.FeatureVector;
import com.zephyr.watch.common.entity.RulPrediction;
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
import java.util.Map;

public class RulPredictFunction extends RichMapFunction<FeatureVector, RulPrediction> {

    private transient Evaluator evaluator;
    private final String pmmlFilePath;

    public RulPredictFunction(String pmmlFilePath) {
        this.pmmlFilePath = pmmlFilePath;
    }

    @Override
    public void open(Configuration parameters) throws Exception {
        super.open(parameters);
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
    public RulPrediction map(FeatureVector fv) throws Exception {
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();

        List<? extends InputField> inputFields = evaluator.getInputFields();
        for (InputField inputField : inputFields) {
            String name = inputField.getName();
            Object value = extractValueFromFeatureVector(fv, name);
            arguments.put(name, value);
        }

        Map<String, ?> results = evaluator.evaluate(arguments);

        List<? extends TargetField> targetFields = evaluator.getTargetFields();
        TargetField targetField = targetFields.get(0);
        Object targetValue = results.get(targetField.getName());

        Double rulPrediction = (Double) EvaluatorUtil.decode(targetValue);

        return new RulPrediction(fv.getMachineId(), fv.getWindowEnd(), rulPrediction);
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

            default: return 0.0;
        }
    }
}
