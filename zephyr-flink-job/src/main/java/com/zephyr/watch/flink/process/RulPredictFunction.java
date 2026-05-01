package com.zephyr.watch.flink.process;

import com.zephyr.watch.common.entity.FeatureVector;
import com.zephyr.watch.common.entity.RulPrediction;
import org.apache.flink.api.common.functions.RichMapFunction;
import org.apache.flink.configuration.Configuration;
import org.jpmml.evaluator.*;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class RulPredictFunction extends RichMapFunction<FeatureVector, RulPrediction> {

    // PMML 璇勪及鍣ㄥ疄渚?
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
        Map<String, Object> arguments = new LinkedHashMap<>();

        // 鑷姩鎻愬彇妯″瀷鎵€闇€鐨勮緭鍏ョ壒寰?
        List<? extends InputField> inputFields = evaluator.getInputFields();
        for (InputField inputField : inputFields) {
            // JPMML 1.6.4 鏋佸叾绮剧畝鐨?API锛岀洿鎺ヨ繑鍥?String锛?
            String name = inputField.getName();
            Object value = extractValueFromFeatureVector(fv, name);
            arguments.put(name, value);
        }

        // 姣绾ф墽琛屾ā鍨嬮娴?
        Map<String, ?> results = evaluator.evaluate(arguments);

        // 鎻愬彇棰勬祴缁撴灉 RUL
        List<? extends TargetField> targetFields = evaluator.getTargetFields();
        TargetField targetField = targetFields.get(0);
        Object targetValue = results.get(targetField.getName());

        Double rulPrediction = (Double) EvaluatorUtil.decode(targetValue);

        return new RulPrediction(fv.getMachineId(), fv.getWindowEnd(), rulPrediction);
    }

    // 杈呭姪鏂规硶锛氭妸 FeatureVector 鐨勫睘鎬ф彁鍙栧嚭鏉ュ杺缁欐ā鍨?
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
