#!/usr/bin/env bash
set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

PMML_PATH="${1:-zephyr-flink-job/src/main/resources/models/model.pmml}"
MODEL_VERSION="${2:-risk-classifier-rest-v1}"
DEBUG_PRINT="${3:-false}"
MODEL_SERVICE_URL="${4:-http://localhost:5001/api/risk/score}"
PYTHON_EXE="${ZEPHYR_PYTHON_EXE:-$ROOT/zephyr-ml/.venv1/Scripts/python.exe}"
if [ ! -x "$PYTHON_EXE" ]; then
  PYTHON_EXE="${ZEPHYR_PYTHON_EXE:-python}"
fi

echo "[1/7] Building modules..."
mvn -q -DskipTests install

echo "[2/7] Starting Spring Boot API..."
mvn -q -f zephyr-api/pom.xml spring-boot:run &

echo "[3/7] Starting Python Risk Model Service..."
(
  cd zephyr-ml
  "$PYTHON_EXE" serve/risk_model_service.py
) &

echo "[4/7] Starting OnlineInferenceJob..."
mvn -q -f zephyr-flink-job/pom.xml org.codehaus.mojo:exec-maven-plugin:3.3.0:java \
  -Dexec.mainClass=com.zephyr.watch.flink.app.OnlineInferenceJob \
  -Dexec.args="$PMML_PATH $MODEL_VERSION $DEBUG_PRINT $MODEL_SERVICE_URL" &

echo "[5/7] Starting RecommendJob..."
mvn -q -f zephyr-flink-job/pom.xml org.codehaus.mojo:exec-maven-plugin:3.3.0:java \
  -Dexec.mainClass=com.zephyr.watch.flink.app.RecommendJob \
  -Dexec.args="$DEBUG_PRINT" &

echo "[6/7] Starting AlertReviewJob..."
mvn -q -f zephyr-flink-job/pom.xml org.codehaus.mojo:exec-maven-plugin:3.3.0:java \
  -Dexec.mainClass=com.zephyr.watch.flink.app.AlertReviewJob &

echo "[7/7] Starting SensorDataProducer..."
mvn -q -f zephyr-producer/pom.xml org.codehaus.mojo:exec-maven-plugin:3.3.0:java \
  -Dexec.mainClass=com.zephyr.watch.producer.SensorDataProducer &

wait
