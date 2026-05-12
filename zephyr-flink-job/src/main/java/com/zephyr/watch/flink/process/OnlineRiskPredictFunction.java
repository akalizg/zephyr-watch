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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 升级版：支持模型动态热加载的预测函数
 */
public class OnlineRiskPredictFunction extends RichMapFunction<FeatureVector, RiskPrediction> {

    private static final Logger LOG = LoggerFactory.getLogger(OnlineRiskPredictFunction.class);

    private String pmmlFilePath;
    private String modelVersion;
    private transient Evaluator evaluator;

    // --- 新增：用于热更新的控制变量 ---
    private long lastLoadTime = 0L;
    // 每 5 分钟检查一次模型文件是否变动（生产环境可根据需求调整）
    private static final long RELOAD_INTERVAL_MS = 5 * 60 * 1000;

    public OnlineRiskPredictFunction(String pmmlFilePath, String modelVersion) {
        this.pmmlFilePath = pmmlFilePath;
        this.modelVersion = modelVersion;
    }

    @Override
    public void open(Configuration parameters) throws Exception {
        loadModel();
    }

    /**
     * 核心逻辑：加载/重载 PMML 模型
     */
    private synchronized void loadModel() throws Exception {
        LOG.info("[ML-FLOW] 正在尝试加载模型版本: {}, 路径: {}", modelVersion, pmmlFilePath);
        try {
            File pmmlFile = resolvePmmlFile();
            this.evaluator = new LoadingModelEvaluatorBuilder()
                    .load(pmmlFile)
                    .build();
            this.evaluator.verify();
            this.lastLoadTime = System.currentTimeMillis();
            LOG.info("[ML-FLOW] 模型加载成功。上次更新时间: {}", lastLoadTime);
        } catch (Exception e) {
            LOG.error("[ML-FLOW] 模型加载失败！将使用旧模型或抛出异常", e);
            if (this.evaluator == null) throw e;
        }
    }

    private File resolvePmmlFile() {
        File configuredPath = new File(pmmlFilePath);
        if (configuredPath.exists()) return configuredPath;

        File modulePath = new File("zephyr-flink-job", pmmlFilePath);
        if (modulePath.exists()) return modulePath;

        throw new IllegalArgumentException("未找到 PMML 模型文件: " + configuredPath.getPath());
    }

    @Override
    public RiskPrediction map(FeatureVector fv) throws Exception {
        // --- 新增：热更新触发检查 ---
        // 逻辑：如果达到了检查间隔，尝试重新加载模型（实现不重启 Job 更新）
        if (System.currentTimeMillis() - lastLoadTime > RELOAD_INTERVAL_MS) {
            // 在实际工程中，这里可以先请求 API 获取最新的 modelPath，如果 path 变了再 loadModel
            loadModel();
        }

        Double rul = predictRul(fv);
        double riskProbability = riskProbability(fv, rul);
        int riskLabel = riskProbability >= JobConfig.RISK_ALERT_THRESHOLD ? 1 : 0;
        String riskLevel = riskLevel(riskProbability, rul);

        String predictionId = String.format(
                Locale.US, "%d-%d-%s",
                fv.getMachineId(), fv.getWindowEnd(), modelVersion
        );

        RiskPrediction prediction = new RiskPrediction(
                predictionId, fv.getMachineId(), fv.getWindowStart(), fv.getWindowEnd(),
                fv.getCycleStart(), fv.getCycleEnd(), rul, riskProbability,
                riskLabel, riskLevel, modelVersion, System.currentTimeMillis()
        );
        copyFeatures(fv, prediction);
        return prediction;
    }

    // --- 以下逻辑保持不变 ---

    private Double predictRul(FeatureVector fv) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        List<? extends InputField> inputFields = evaluator.getInputFields();
        for (InputField inputField : inputFields) {
            String name = inputField.getName();
            arguments.put(name, extractValueFromFeatureVector(fv, name));
        }

        Map<String, ?> results = evaluator.evaluate(arguments);
        TargetField targetField = evaluator.getTargetFields().get(0);
        Object targetValue = results.get(targetField.getName());
        Object decoded = EvaluatorUtil.decode(targetValue);
        return (decoded instanceof Number) ? ((Number) decoded).doubleValue() : Double.valueOf(String.valueOf(decoded));
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
        return (max <= min) ? 0.0D : Math.max(0.0D, Math.min(1.0D, (value - min) / (max - min)));
    }

    private String riskLevel(double riskProbability, double rul) {
        if (riskProbability >= JobConfig.RISK_CRITICAL_THRESHOLD || rul <= JobConfig.RUL_CRITICAL_THRESHOLD) return "CRITICAL";
        if (riskProbability >= JobConfig.RISK_ALERT_THRESHOLD || rul <= JobConfig.RUL_WARNING_THRESHOLD) return "HIGH";
        return (riskProbability >= 0.45D) ? "MEDIUM" : "LOW";
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