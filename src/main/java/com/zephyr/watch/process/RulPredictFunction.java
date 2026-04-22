package com.zephyr.watch.process;

import com.zephyr.watch.model.FeatureVector;
import com.zephyr.watch.model.RulPrediction;
import org.apache.flink.api.common.functions.RichMapFunction;
import org.apache.flink.configuration.Configuration;
import org.jpmml.evaluator.*;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class RulPredictFunction extends RichMapFunction<FeatureVector, RulPrediction> {

    // PMML 评估器实例
    private transient Evaluator evaluator;
    private final String pmmlFilePath;

    public RulPredictFunction(String pmmlFilePath) {
        this.pmmlFilePath = pmmlFilePath;
    }

    @Override
    public void open(Configuration parameters) throws Exception {
        super.open(parameters);
        // 加载并校验本地的 PMML 模型文件
        File pmmlFile = new File(pmmlFilePath);
        evaluator = new LoadingModelEvaluatorBuilder()
                .load(pmmlFile)
                .build();
        evaluator.verify();
    }

    @Override
    public RulPrediction map(FeatureVector fv) throws Exception {
        Map<String, Object> arguments = new LinkedHashMap<>();

        // 自动提取模型所需的输入特征
        List<? extends InputField> inputFields = evaluator.getInputFields();
        for (InputField inputField : inputFields) {
            // JPMML 1.6.4 极其精简的 API，直接返回 String！
            String name = inputField.getName();
            Object value = extractValueFromFeatureVector(fv, name);
            arguments.put(name, value);
        }

        // 毫秒级执行模型预测
        Map<String, ?> results = evaluator.evaluate(arguments);

        // 提取预测结果 RUL
        List<? extends TargetField> targetFields = evaluator.getTargetFields();
        TargetField targetField = targetFields.get(0);
        Object targetValue = results.get(targetField.getName());

        Double rulPrediction = (Double) EvaluatorUtil.decode(targetValue);

        return new RulPrediction(fv.getMachineId(), fv.getWindowEnd(), rulPrediction);
    }

    // 辅助方法：把 FeatureVector 的属性提取出来喂给模型
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