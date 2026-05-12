package com.zephyr.watch.flink.process;

import com.alibaba.fastjson2.JSON;
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
import java.util.Locale;
import org.apache.flink.api.common.functions.RichMapFunction;
import org.apache.flink.configuration.Configuration;

public class RestRiskPredictFunction extends RichMapFunction<FeatureVector, RiskPrediction> {

    private final String pmmlPath;
    private final String modelServiceUrl;
    private final String defaultModelVersion;
    private transient OnlineRiskPredictFunction rulPredictFunction;

    public RestRiskPredictFunction(String modelServiceUrl, String defaultModelVersion) {
        this(null, modelServiceUrl, defaultModelVersion);
    }

    public RestRiskPredictFunction(String pmmlPath, String modelServiceUrl, String defaultModelVersion) {
        this.pmmlPath = pmmlPath;
        this.modelServiceUrl = modelServiceUrl;
        this.defaultModelVersion = defaultModelVersion;
    }

    @Override
    public void open(Configuration parameters) throws Exception {
        if (pmmlPath != null && !pmmlPath.trim().isEmpty()) {
            rulPredictFunction = new OnlineRiskPredictFunction(pmmlPath, defaultModelVersion);
            rulPredictFunction.open(parameters);
        }
    }

    @Override
    public RiskPrediction map(FeatureVector fv) throws Exception {
        Double rul = -1.0D;
        if (rulPredictFunction != null) {
            RiskPrediction rulPrediction = rulPredictFunction.map(fv);
            rul = rulPrediction.getRul();
        }

        JSONObject response = JSON.parseObject(postJson(modelServiceUrl, buildRequestJson(fv)));
        if (!response.getBooleanValue("success")) {
            throw new IllegalStateException("risk model service returned failure: " + response);
        }

        double riskProbability = response.getDoubleValue("riskProbability");
        int riskLabel = response.getIntValue("riskLabel");
        String riskLevel = response.getString("riskLevel");
        String modelVersion = response.getString("modelVersion");
        if (modelVersion == null || modelVersion.trim().isEmpty()) {
            modelVersion = defaultModelVersion;
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

    private String buildRequestJson(FeatureVector fv) {
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
            throw new IllegalStateException("risk model service HTTP " + status + ": " + response);
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
}
