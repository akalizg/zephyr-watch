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
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.async.ResultFuture;
import org.apache.flink.streaming.api.functions.async.RichAsyncFunction;

public class AsyncRestRiskPredictFunction extends RichAsyncFunction<FeatureVector, RiskPrediction> {

    private final String pmmlPath;
    private final String modelServiceUrl;
    private final String defaultModelVersion;
    private final int workerThreads;
    private transient OnlineRiskPredictFunction rulPredictFunction;
    private transient ExecutorService executorService;
    private transient Map<ResultFuture<RiskPrediction>, InflightRequest> inflightRequests;

    public AsyncRestRiskPredictFunction(String pmmlPath,
                                        String modelServiceUrl,
                                        String defaultModelVersion,
                                        int workerThreads) {
        this.pmmlPath = pmmlPath;
        this.modelServiceUrl = modelServiceUrl;
        this.defaultModelVersion = defaultModelVersion;
        this.workerThreads = Math.max(1, workerThreads);
    }

    @Override
    public void open(Configuration parameters) throws Exception {
        if (pmmlPath != null && !pmmlPath.trim().isEmpty()) {
            rulPredictFunction = new OnlineRiskPredictFunction(pmmlPath, defaultModelVersion);
            rulPredictFunction.open(parameters);
        }
        executorService = Executors.newFixedThreadPool(workerThreads, new NamedThreadFactory("zephyr-async-risk"));
        inflightRequests = new ConcurrentHashMap<>();
    }

    @Override
    public void asyncInvoke(final FeatureVector fv, final ResultFuture<RiskPrediction> resultFuture) {
        final Double rul = predictRulSafely(fv);
        final InflightRequest inflight = new InflightRequest(rul);
        inflightRequests.put(resultFuture, inflight);

        try {
            Future<?> worker = executorService.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        RiskPrediction prediction = buildPredictionWithFallback(fv, inflight.rul, null);
                        completeOnce(resultFuture, inflight, prediction);
                    } catch (Exception e) {
                        RiskPrediction fallback = buildPredictionWithFallback(fv, inflight.rul, e.getMessage());
                        completeOnce(resultFuture, inflight, fallback);
                    }
                }
            });
            inflight.worker.set(worker);
        } catch (RejectedExecutionException e) {
            RiskPrediction fallback = buildPredictionWithFallback(fv, rul, "async-rejected");
            completeOnce(resultFuture, inflight, fallback);
        }
    }

    @Override
    public void timeout(FeatureVector fv, ResultFuture<RiskPrediction> resultFuture) {
        InflightRequest inflight = inflightRequests.get(resultFuture);
        if (inflight == null) {
            return;
        }
        Future<?> worker = inflight.worker.get();
        if (worker != null) {
            worker.cancel(true);
        }
        RiskPrediction prediction = buildPredictionWithFallback(fv, inflight.rul, "async-timeout");
        completeOnce(resultFuture, inflight, prediction);
    }

    @Override
    public void close() throws Exception {
        if (executorService != null) {
            executorService.shutdownNow();
        }
        if (inflightRequests != null) {
            inflightRequests.clear();
        }
        if (rulPredictFunction != null) {
            rulPredictFunction.close();
        }
        super.close();
    }

    private RiskPrediction buildPredictionWithFallback(FeatureVector fv, Double rul, String timeoutReason) {
        double riskProbability;
        int riskLabel;
        String riskLevel;
        String modelVersion;

        try {
            if (timeoutReason != null) {
                throw new IllegalStateException(timeoutReason);
            }
            JSONObject response = JSON.parseObject(postJson(modelServiceUrl, buildRequestJson(fv)));
            if (!response.getBooleanValue("success")) {
                throw new IllegalStateException("risk model service returned failure: " + response);
            }

            riskProbability = response.getDoubleValue("riskProbability");
            riskLabel = response.getIntValue("riskLabel");
            riskLevel = response.getString("riskLevel");
            modelVersion = response.getString("modelVersion");
            if (modelVersion == null || modelVersion.trim().isEmpty()) {
                modelVersion = defaultModelVersion;
            }
        } catch (Exception e) {
            riskProbability = fallbackRiskProbability(fv);
            riskLabel = riskProbability >= 0.70D ? 1 : 0;
            riskLevel = riskProbability >= 0.90D ? "CRITICAL" : (riskProbability >= 0.70D ? "HIGH" : "LOW");
            modelVersion = defaultModelVersion + "-fallback";
            System.err.println("AsyncRestRiskPredictFunction fallback: " + e.getMessage());
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

    private Double predictRulSafely(FeatureVector fv) {
        if (rulPredictFunction == null) {
            return -1.0D;
        }
        try {
            RiskPrediction rulPrediction = rulPredictFunction.map(fv);
            return rulPrediction.getRul();
        } catch (Exception e) {
            System.err.println("AsyncRestRiskPredictFunction RUL fallback: " + e.getMessage());
            return -1.0D;
        }
    }

    private void completeOnce(ResultFuture<RiskPrediction> resultFuture,
                              InflightRequest inflight,
                              RiskPrediction prediction) {
        if (!inflight.completed.compareAndSet(false, true)) {
            return;
        }
        inflightRequests.remove(resultFuture);
        resultFuture.complete(Collections.singletonList(prediction));
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

    private static final class NamedThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger counter = new AtomicInteger(1);

        private NamedThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, prefix + "-" + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }

    private static final class InflightRequest {
        private final Double rul;
        private final AtomicBoolean completed = new AtomicBoolean(false);
        private final AtomicReference<Future<?>> worker = new AtomicReference<>();

        private InflightRequest(Double rul) {
            this.rul = rul;
        }
    }
}
