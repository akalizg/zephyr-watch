package com.zephyr.watch.flink.process;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.zephyr.watch.common.entity.FeatureVector;
import com.zephyr.watch.common.entity.RiskPrediction;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.flink.api.common.functions.RichMapFunction;
import org.apache.flink.configuration.Configuration;

public class MultiBackendRiskPredictFunction extends RichMapFunction<FeatureVector, RiskPrediction> {

    private static final String BACKEND_REST = "rest";
    private static final String BACKEND_ONNX = "onnx";
    private static final String BACKEND_TF_SERVING = "tf-serving";

    private final String pmmlPath;
    private final String defaultModelVersion;
    private final String restServiceUrl;
    private final String tfServingUrl;
    private final String backend;
    private final String onnxModelPath;
    private final String onnxInputName;
    private final String onnxOutputName;
    private final String tfServingInputName;
    private final String tfServingSignatureName;

    private transient OrtEnvironment ortEnvironment;
    private transient OrtSession ortSession;
    private transient String effectiveOnnxInputName;
    private transient String effectiveOnnxOutputName;
    private transient String onnxInitFailure;
    private transient boolean onnxRestBridgeLogged;
    private transient OnlineRiskPredictFunction rulPredictFunction;

    public MultiBackendRiskPredictFunction(String pmmlPath,
                                           String defaultModelVersion,
                                           String restServiceUrl,
                                           String tfServingUrl,
                                           String backend,
                                           String onnxModelPath,
                                           String onnxInputName,
                                           String onnxOutputName,
                                           String tfServingInputName,
                                           String tfServingSignatureName) {
        this.pmmlPath = pmmlPath;
        this.defaultModelVersion = defaultModelVersion;
        this.restServiceUrl = restServiceUrl;
        this.tfServingUrl = tfServingUrl;
        this.backend = normalizeBackend(backend);
        this.onnxModelPath = onnxModelPath;
        this.onnxInputName = onnxInputName;
        this.onnxOutputName = onnxOutputName;
        this.tfServingInputName = tfServingInputName;
        this.tfServingSignatureName = tfServingSignatureName;
    }

    @Override
    public void open(Configuration parameters) throws Exception {
        if (pmmlPath != null && !pmmlPath.trim().isEmpty()) {
            rulPredictFunction = new OnlineRiskPredictFunction(pmmlPath, defaultModelVersion);
            rulPredictFunction.open(parameters);
        }
        if (BACKEND_ONNX.equals(backend)) {
            System.out.println("MultiBackendRiskPredictFunction ONNX init start: modelPath=" + onnxModelPath);
            try {
                initOnnxSession();
            } catch (Exception | LinkageError e) {
                onnxInitFailure = e.getClass().getSimpleName() + ": " + e.getMessage();
                System.err.println("MultiBackendRiskPredictFunction ONNX init failed: " + onnxInitFailure);
            }
        }
    }

    @Override
    public RiskPrediction map(FeatureVector fv) throws Exception {
        Double rul = -1.0D;
        if (rulPredictFunction != null) {
            try {
                RiskPrediction rulPrediction = rulPredictFunction.map(fv);
                rul = rulPrediction.getRul();
            } catch (Exception e) {
                System.err.println("MultiBackendRiskPredictFunction RUL fallback: " + e.getMessage());
            }
        }

        double riskProbability;
        int riskLabel;
        String riskLevel;
        String modelVersion;

        try {
            BackendResult result = inferByBackend(fv);
            riskProbability = result.riskProbability;
            riskLabel = result.riskLabel;
            riskLevel = result.riskLevel;
            modelVersion = result.modelVersion;
        } catch (Exception | LinkageError e) {
            riskProbability = fallbackRiskProbability(fv);
            riskLabel = riskProbability >= 0.70D ? 1 : 0;
            riskLevel = riskProbability >= 0.90D ? "CRITICAL" : (riskProbability >= 0.70D ? "HIGH" : "LOW");
            modelVersion = defaultModelVersion + "-fallback";
            System.err.println("MultiBackendRiskPredictFunction fallback: backend=" + backend + ", " + e.getMessage());
        }

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

    @Override
    public void close() throws Exception {
        if (ortSession != null) {
            try {
                ortSession.close();
            } catch (Exception ignored) {
            } finally {
                ortSession = null;
            }
        }
        super.close();
    }

    private BackendResult inferByBackend(FeatureVector fv) throws Exception {
        if (BACKEND_ONNX.equals(backend)) {
            try {
                return inferOnnx(fv);
            } catch (Exception | LinkageError e) {
                if (canUseRestBridge()) {
                    logOnnxRestBridge(e);
                    return inferRestAsBridge(fv);
                }
                throw e;
            }
        }
        if (BACKEND_TF_SERVING.equals(backend)) {
            return inferTfServing(fv);
        }
        return inferRest(fv);
    }

    private BackendResult inferRest(FeatureVector fv) throws Exception {
        JSONObject response = JSON.parseObject(postJson(restServiceUrl, buildFeatureJson(fv)));
        if (!response.getBooleanValue("success")) {
            throw new IllegalStateException("risk model service returned failure: " + response);
        }
        double probability = response.getDoubleValue("riskProbability");
        int label = response.getIntValue("riskLabel");
        String level = response.getString("riskLevel");
        String modelVersion = response.getString("modelVersion");
        if (modelVersion == null || modelVersion.trim().isEmpty()) {
            modelVersion = defaultModelVersion;
        }
        return new BackendResult(probability, label, level, modelVersion);
    }

    private BackendResult inferTfServing(FeatureVector fv) throws Exception {
        String url = tfServingUrl;
        if (url == null || url.trim().isEmpty()) {
            throw new IllegalStateException("TF Serving URL is empty");
        }

        JSONObject response = JSON.parseObject(postJson(url, buildTfServingRequest(fv)));
        Object predictionRaw = extractTfPredictionRaw(response);
        double probability = extractProbabilityFromObject(predictionRaw);
        int label = probability >= 0.70D ? 1 : 0;
        String level = probability >= 0.90D ? "CRITICAL" : (probability >= 0.70D ? "HIGH" : "LOW");

        String modelVersion = response.getString("modelVersion");
        if (modelVersion == null || modelVersion.trim().isEmpty()) {
            modelVersion = defaultModelVersion + "-tf-serving";
        }
        return new BackendResult(probability, label, level, modelVersion);
    }

    private BackendResult inferOnnx(FeatureVector fv) throws Exception {
        if (ortSession == null || ortEnvironment == null) {
            String reason = onnxInitFailure == null ? "unknown" : onnxInitFailure;
            throw new IllegalStateException("ONNX session is not initialized, reason=" + reason);
        }

        float[][] featureRow = new float[][]{toFeatureArray(fv)};
        try (OnnxTensor tensor = OnnxTensor.createTensor(ortEnvironment, featureRow)) {
            Map<String, OnnxTensor> inputMap = new HashMap<>();
            inputMap.put(effectiveOnnxInputName, tensor);
            try (OrtSession.Result result = ortSession.run(inputMap)) {
                double probability = extractOnnxProbability(result);
                int label = probability >= 0.70D ? 1 : 0;
                String level = probability >= 0.90D ? "CRITICAL" : (probability >= 0.70D ? "HIGH" : "LOW");
                return new BackendResult(probability, label, level, defaultModelVersion + "-onnx");
            }
        }
    }

    private double extractOnnxProbability(OrtSession.Result result) throws Exception {
        if (effectiveOnnxOutputName != null && !effectiveOnnxOutputName.isEmpty()) {
            Optional<OnnxValue> value = result.get(effectiveOnnxOutputName);
            if (value.isPresent()) {
                return extractProbabilityFromObject(value.get().getValue());
            }
        }

        for (Map.Entry<String, OnnxValue> entry : result) {
            double probability = extractProbabilityFromObject(entry.getValue().getValue());
            if (probability >= 0.0D) {
                return probability;
            }
        }

        throw new IllegalStateException("failed to parse ONNX output probability");
    }

    private Object extractTfPredictionRaw(JSONObject response) {
        JSONArray predictions = response.getJSONArray("predictions");
        if (predictions != null && !predictions.isEmpty()) {
            return predictions.get(0);
        }
        if (response.containsKey("outputs")) {
            return response.get("outputs");
        }
        throw new IllegalStateException("TF Serving response missing predictions/outputs");
    }

    private double extractProbabilityFromObject(Object outputValue) {
        if (outputValue == null) {
            return -1.0D;
        }

        if (outputValue instanceof Number) {
            return ((Number) outputValue).doubleValue();
        }

        if (outputValue instanceof float[][]) {
            float[][] values = (float[][]) outputValue;
            return pickProbability(values.length > 0 ? values[0] : null);
        }
        if (outputValue instanceof float[]) {
            return pickProbability((float[]) outputValue);
        }
        if (outputValue instanceof double[][]) {
            double[][] values = (double[][]) outputValue;
            return pickProbability(values.length > 0 ? values[0] : null);
        }
        if (outputValue instanceof double[]) {
            return pickProbability((double[]) outputValue);
        }
        if (outputValue instanceof int[][]) {
            int[][] values = (int[][]) outputValue;
            return pickProbability(values.length > 0 ? values[0] : null);
        }
        if (outputValue instanceof int[]) {
            return pickProbability((int[]) outputValue);
        }
        if (outputValue instanceof long[][]) {
            long[][] values = (long[][]) outputValue;
            return pickProbability(values.length > 0 ? values[0] : null);
        }
        if (outputValue instanceof long[]) {
            return pickProbability((long[]) outputValue);
        }

        if (outputValue instanceof JSONArray) {
            JSONArray array = (JSONArray) outputValue;
            if (array.isEmpty()) {
                return -1.0D;
            }
            Object first = array.get(0);
            if (first instanceof Number) {
                return pickProbability(array);
            }
            return extractProbabilityFromObject(first);
        }

        if (outputValue instanceof JSONObject) {
            JSONObject obj = (JSONObject) outputValue;
            if (obj.containsKey("riskProbability")) {
                return obj.getDoubleValue("riskProbability");
            }
            if (obj.containsKey("probabilities")) {
                return extractProbabilityFromObject(obj.get("probabilities"));
            }
            if (obj.containsKey("outputs")) {
                return extractProbabilityFromObject(obj.get("outputs"));
            }
            if (!obj.isEmpty()) {
                String firstKey = obj.keySet().iterator().next();
                return extractProbabilityFromObject(obj.get(firstKey));
            }
        }

        if (outputValue instanceof Map) {
            return pickProbabilityFromMap((Map<?, ?>) outputValue);
        }

        if (outputValue instanceof Iterable) {
            for (Object item : (Iterable<?>) outputValue) {
                double probability = extractProbabilityFromObject(item);
                if (probability >= 0.0D) {
                    return probability;
                }
            }
            return -1.0D;
        }

        return -1.0D;
    }

    private double pickProbability(float[] values) {
        if (values == null || values.length == 0) {
            return -1.0D;
        }
        return values.length > 1 ? values[1] : values[0];
    }

    private double pickProbability(double[] values) {
        if (values == null || values.length == 0) {
            return -1.0D;
        }
        return values.length > 1 ? values[1] : values[0];
    }

    private double pickProbability(int[] values) {
        if (values == null || values.length == 0) {
            return -1.0D;
        }
        return values.length > 1 ? values[1] : values[0];
    }

    private double pickProbability(long[] values) {
        if (values == null || values.length == 0) {
            return -1.0D;
        }
        return values.length > 1 ? values[1] : values[0];
    }

    private double pickProbability(JSONArray values) {
        if (values == null || values.isEmpty()) {
            return -1.0D;
        }
        return values.size() > 1 ? values.getDoubleValue(1) : values.getDoubleValue(0);
    }

    private double pickProbabilityFromMap(Map<?, ?> values) {
        if (values == null || values.isEmpty()) {
            return -1.0D;
        }

        Double positiveClassProb = findProbabilityByClassKey(values, 1);
        if (positiveClassProb != null) {
            return positiveClassProb;
        }

        double maxProb = -1.0D;
        for (Object raw : values.values()) {
            if (raw instanceof Number) {
                maxProb = Math.max(maxProb, ((Number) raw).doubleValue());
            }
        }
        return maxProb;
    }

    private Double findProbabilityByClassKey(Map<?, ?> values, int targetClass) {
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            Integer classKey = asInt(entry.getKey());
            if (classKey != null && classKey == targetClass && entry.getValue() instanceof Number) {
                return ((Number) entry.getValue()).doubleValue();
            }
        }
        return null;
    }

    private Integer asInt(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt(((String) value).trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String buildTfServingRequest(FeatureVector fv) {
        JSONObject body = new JSONObject();
        String signatureName = (tfServingSignatureName == null || tfServingSignatureName.trim().isEmpty())
                ? "serving_default"
                : tfServingSignatureName;
        body.put("signature_name", signatureName);

        float[] features = toFeatureArray(fv);
        JSONArray featureValues = new JSONArray();
        for (float feature : features) {
            featureValues.add(feature);
        }

        JSONArray instances = new JSONArray();
        if (tfServingInputName == null || tfServingInputName.trim().isEmpty()) {
            instances.add(featureValues);
        } else {
            JSONObject instance = new JSONObject();
            instance.put(tfServingInputName, featureValues);
            instances.add(instance);
        }

        body.put("instances", instances);
        return body.toJSONString();
    }

    private String buildFeatureJson(FeatureVector fv) {
        JSONObject json = new JSONObject();
        json.put("sampleCount", fv.getSampleCount());
        json.put("cycleStart", fv.getCycleStart());
        json.put("cycleEnd", fv.getCycleEnd());
        json.put("pressureMin", fv.getPressureMin());
        json.put("pressureMax", fv.getPressureMax());
        json.put("pressureAvg", fv.getPressureAvg());
        json.put("pressureStd", fv.getPressureStd());
        json.put("pressureTrend", fv.getPressureTrend());
        json.put("temperatureMin", fv.getTemperatureMin());
        json.put("temperatureMax", fv.getTemperatureMax());
        json.put("temperatureAvg", fv.getTemperatureAvg());
        json.put("temperatureStd", fv.getTemperatureStd());
        json.put("temperatureTrend", fv.getTemperatureTrend());
        json.put("speedMin", fv.getSpeedMin());
        json.put("speedMax", fv.getSpeedMax());
        json.put("speedAvg", fv.getSpeedAvg());
        json.put("speedStd", fv.getSpeedStd());
        json.put("speedTrend", fv.getSpeedTrend());
        return json.toJSONString();
    }

    private String postJson(String url, String requestJson) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(5000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");

        byte[] body = requestJson.getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(body.length);
        try (OutputStream os = connection.getOutputStream()) {
            os.write(body);
        }

        int status = connection.getResponseCode();
        InputStream stream = status >= 200 && status < 300
                ? connection.getInputStream()
                : connection.getErrorStream();
        String response = readAll(stream);
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("backend service HTTP " + status + ": " + response);
        }
        return response;
    }

    private String readAll(InputStream stream) throws Exception {
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
        }
        return builder.toString();
    }

    private float[] toFeatureArray(FeatureVector fv) {
        return new float[]{
                safeFloat(fv.getSampleCount()),
                safeFloat(fv.getCycleStart()),
                safeFloat(fv.getCycleEnd()),
                safeFloat(fv.getPressureMin()),
                safeFloat(fv.getPressureMax()),
                safeFloat(fv.getPressureAvg()),
                safeFloat(fv.getPressureStd()),
                safeFloat(fv.getPressureTrend()),
                safeFloat(fv.getTemperatureMin()),
                safeFloat(fv.getTemperatureMax()),
                safeFloat(fv.getTemperatureAvg()),
                safeFloat(fv.getTemperatureStd()),
                safeFloat(fv.getTemperatureTrend()),
                safeFloat(fv.getSpeedMin()),
                safeFloat(fv.getSpeedMax()),
                safeFloat(fv.getSpeedAvg()),
                safeFloat(fv.getSpeedStd()),
                safeFloat(fv.getSpeedTrend())
        };
    }

    private float safeFloat(Number value) {
        if (value == null) {
            return 0.0F;
        }
        return value.floatValue();
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

    private double fallbackRiskProbability(FeatureVector fv) {
        double temperatureSignal = normalize(fv.getTemperatureAvg(), 1580.0D, 1610.0D);
        double speedTrendSignal = normalize(Math.abs(fv.getSpeedTrend()), 0.0D, 25.0D);
        double pressureStdSignal = normalize(fv.getPressureStd(), 0.0D, 1.0D);
        double probability = temperatureSignal * 0.50D + speedTrendSignal * 0.30D + pressureStdSignal * 0.20D;
        return Math.max(0.0D, Math.min(1.0D, probability));
    }

    private double normalize(double value, double min, double max) {
        if (max <= min) {
            return 0.0D;
        }
        return Math.max(0.0D, Math.min(1.0D, (value - min) / (max - min)));
    }

    private String normalizeBackend(String value) {
        if (value == null || value.trim().isEmpty()) {
            return BACKEND_REST;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if ("tf".equals(normalized) || "tfserving".equals(normalized) || "tf-serving".equals(normalized)) {
            return BACKEND_TF_SERVING;
        }
        if ("onnx".equals(normalized)) {
            return BACKEND_ONNX;
        }
        return BACKEND_REST;
    }

    private void initOnnxSession() throws Exception {
        String modelPath = onnxModelPath == null ? "" : onnxModelPath.trim();
        if (modelPath.isEmpty()) {
            throw new IllegalStateException("ONNX backend requires ZEPHYR_ONNX_MODEL_PATH");
        }

        ortEnvironment = OrtEnvironment.getEnvironment();
        ortSession = ortEnvironment.createSession(modelPath, new OrtSession.SessionOptions());

        Set<String> inputNames = ortSession.getInputNames();
        if (inputNames == null || inputNames.isEmpty()) {
            throw new IllegalStateException("ONNX model has no input names");
        }
        if (onnxInputName != null && !onnxInputName.trim().isEmpty()) {
            effectiveOnnxInputName = onnxInputName.trim();
        } else {
            effectiveOnnxInputName = inputNames.iterator().next();
        }

        Set<String> outputNames = ortSession.getOutputNames();
        if (onnxOutputName != null && !onnxOutputName.trim().isEmpty()) {
            effectiveOnnxOutputName = onnxOutputName.trim();
        } else if (outputNames != null && !outputNames.isEmpty()) {
            effectiveOnnxOutputName = pickBestOnnxOutputName(outputNames);
        } else {
            effectiveOnnxOutputName = null;
        }

        System.out.println("MultiBackendRiskPredictFunction ONNX initialized: input="
                + effectiveOnnxInputName + ", output=" + effectiveOnnxOutputName);
    }

    private String pickBestOnnxOutputName(Set<String> outputNames) {
        String probabilityLike = null;
        String fallback = null;
        for (String name : outputNames) {
            if (name == null || name.trim().isEmpty()) {
                continue;
            }
            String normalized = name.trim().toLowerCase(Locale.ROOT);
            if (normalized.contains("probability") || normalized.contains("probabilities") || normalized.contains("prob")) {
                probabilityLike = name.trim();
                break;
            }
            if (fallback == null || name.compareToIgnoreCase(fallback) < 0) {
                fallback = name.trim();
            }
        }
        return probabilityLike != null ? probabilityLike : fallback;
    }

    private static final class BackendResult {
        private final double riskProbability;
        private final int riskLabel;
        private final String riskLevel;
        private final String modelVersion;

        private BackendResult(double riskProbability, int riskLabel, String riskLevel, String modelVersion) {
            this.riskProbability = riskProbability;
            this.riskLabel = riskLabel;
            this.riskLevel = riskLevel;
            this.modelVersion = modelVersion;
        }
    }

    private boolean canUseRestBridge() {
        return restServiceUrl != null && !restServiceUrl.trim().isEmpty();
    }

    private BackendResult inferRestAsBridge(FeatureVector fv) throws Exception {
        BackendResult rest = inferRest(fv);
        String bridgeVersion = rest.modelVersion;
        if (bridgeVersion == null || bridgeVersion.trim().isEmpty()) {
            bridgeVersion = defaultModelVersion;
        }
        if (!bridgeVersion.endsWith("-rest-bridge")) {
            bridgeVersion = bridgeVersion + "-rest-bridge";
        }
        return new BackendResult(rest.riskProbability, rest.riskLabel, rest.riskLevel, bridgeVersion);
    }

    private void logOnnxRestBridge(Throwable cause) {
        if (!onnxRestBridgeLogged) {
            onnxRestBridgeLogged = true;
            System.err.println("MultiBackendRiskPredictFunction ONNX unavailable, bridging to REST. cause="
                    + cause.getClass().getSimpleName() + ": " + cause.getMessage());
        }
    }
}
