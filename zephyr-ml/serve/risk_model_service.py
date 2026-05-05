import json
import os
import subprocess
import sys
from typing import Any, Dict, Tuple

import joblib
import pandas as pd
from flask import Flask, jsonify, request

try:
    import requests
except ImportError:  # requests is only required when loading the active model registry entry.
    requests = None

try:
    from minio import Minio
except ImportError:  # MinIO is optional for the local-only MVP.
    Minio = None


CURRENT_DIR = os.path.dirname(os.path.abspath(__file__))
ML_ROOT = os.path.abspath(os.path.join(CURRENT_DIR, ".."))
MODEL_DIR = os.path.join(ML_ROOT, "models")
CACHE_DIR = os.environ.get("ZEPHYR_MODEL_CACHE_DIR", os.path.join(ML_ROOT, ".cache", "active_model"))

DEFAULT_MODEL_PATH = os.path.join(MODEL_DIR, "best_risk_model.pkl")
DEFAULT_THRESHOLD_PATH = os.path.join(MODEL_DIR, "threshold.json")
DEFAULT_FEATURE_COLUMNS_PATH = os.path.join(MODEL_DIR, "feature_columns.json")
DEFAULT_METADATA_PATH = os.path.join(MODEL_DIR, "model_metadata.json")
BOOTSTRAP_MODEL_SCRIPT = os.path.join(ML_ROOT, "tools", "bootstrap_model.py")

MODEL_REGISTRY_ACTIVE_URL = os.environ.get(
    "ZEPHYR_MODEL_REGISTRY_ACTIVE_URL",
    "http://localhost:8080/api/models/active",
)
LOAD_ACTIVE_MODEL = os.environ.get("ZEPHYR_LOAD_ACTIVE_MODEL", "false").lower() == "true"

MINIO_ENDPOINT = os.environ.get("ZEPHYR_MINIO_ENDPOINT", "localhost:9000")
MINIO_ACCESS_KEY = os.environ.get("ZEPHYR_MINIO_ACCESS_KEY", "minioadmin")
MINIO_SECRET_KEY = os.environ.get("ZEPHYR_MINIO_SECRET_KEY", "minioadmin")
MINIO_SECURE = os.environ.get("ZEPHYR_MINIO_SECURE", "false").lower() == "true"

FALLBACK_FEATURE_COLUMNS = [
    "sampleCount",
    "cycleStart",
    "cycleEnd",
    "pressureMin",
    "pressureMax",
    "pressureAvg",
    "pressureStd",
    "pressureTrend",
    "temperatureMin",
    "temperatureMax",
    "temperatureAvg",
    "temperatureStd",
    "temperatureTrend",
    "speedMin",
    "speedMax",
    "speedAvg",
    "speedStd",
    "speedTrend",
]

app = Flask(__name__)

model = None
threshold_config: Dict[str, Any] = {}
feature_config: Dict[str, Any] = {}
metadata: Dict[str, Any] = {}
loaded_paths: Dict[str, str] = {}


def read_json(path: str, fallback: Dict[str, Any]) -> Dict[str, Any]:
    if not os.path.exists(path):
        return dict(fallback)
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def parse_minio_uri(uri: str) -> Tuple[str, str]:
    if not uri.startswith("minio://"):
        raise ValueError("unsupported model uri: %s" % uri)
    bucket_and_object = uri[len("minio://") :]
    bucket, object_name = bucket_and_object.split("/", 1)
    return bucket, object_name


def download_from_minio(uri: str, local_path: str) -> str:
    if Minio is None:
        raise RuntimeError("minio package is required when ZEPHYR_LOAD_ACTIVE_MODEL=true")
    bucket, object_name = parse_minio_uri(uri)
    os.makedirs(os.path.dirname(local_path), exist_ok=True)
    client = Minio(
        MINIO_ENDPOINT,
        access_key=MINIO_ACCESS_KEY,
        secret_key=MINIO_SECRET_KEY,
        secure=MINIO_SECURE,
    )
    client.fget_object(bucket, object_name, local_path)
    return local_path


def unwrap_active_response(payload: Dict[str, Any]) -> Dict[str, Any]:
    data = payload.get("data", payload)
    if isinstance(data, dict) and "modelUri" not in data and "model_uri" not in data:
        nested = data.get("activeModel") or data.get("model")
        if isinstance(nested, dict):
            return nested
    return data


def pick(active: Dict[str, Any], camel: str, snake: str) -> str:
    value = active.get(camel)
    if value is None:
        value = active.get(snake)
    if not value:
        raise ValueError("active model response missing %s/%s" % (camel, snake))
    return str(value)


def resolve_model_files() -> Dict[str, str]:
    if not LOAD_ACTIVE_MODEL:
        return {
            "model": DEFAULT_MODEL_PATH,
            "threshold": DEFAULT_THRESHOLD_PATH,
            "feature_columns": DEFAULT_FEATURE_COLUMNS_PATH,
            "metadata": DEFAULT_METADATA_PATH,
        }

    if requests is None:
        raise RuntimeError("requests package is required when ZEPHYR_LOAD_ACTIVE_MODEL=true")

    response = requests.get(MODEL_REGISTRY_ACTIVE_URL, timeout=5)
    response.raise_for_status()
    active = unwrap_active_response(response.json())

    return {
        "model": download_from_minio(pick(active, "modelUri", "model_uri"), os.path.join(CACHE_DIR, "best_risk_model.pkl")),
        "threshold": download_from_minio(pick(active, "thresholdUri", "threshold_uri"), os.path.join(CACHE_DIR, "threshold.json")),
        "feature_columns": download_from_minio(
            pick(active, "featureColumnsUri", "feature_columns_uri"),
            os.path.join(CACHE_DIR, "feature_columns.json"),
        ),
        "metadata": download_from_minio(pick(active, "metadataUri", "metadata_uri"), os.path.join(CACHE_DIR, "model_metadata.json")),
    }


def ensure_model_file(model_path: str) -> None:
    if os.path.exists(model_path):
        return

    if os.environ.get("ZEPHYR_AUTO_BOOTSTRAP_MODEL", "false").lower() == "true":
        command = [sys.executable, BOOTSTRAP_MODEL_SCRIPT]
        result = subprocess.run(command, cwd=ML_ROOT)
        if result.returncode != 0:
            raise RuntimeError(
                "模型 bootstrap 失败，命令=%s，返回码=%s"
                % (" ".join(command), result.returncode)
            )
        if os.path.exists(model_path):
            return

    raise RuntimeError(
        "缺少 best_risk_model.pkl，无法启动 REST 模型服务。请运行："
        "python zephyr-ml/tools/bootstrap_model.py"
    )


def load_model_state() -> None:
    global model, threshold_config, feature_config, metadata, loaded_paths

    loaded_paths = resolve_model_files()
    ensure_model_file(loaded_paths["model"])
    model = joblib.load(loaded_paths["model"])
    threshold_config = read_json(
        loaded_paths["threshold"],
        {"riskAlertThreshold": 0.7, "riskCriticalThreshold": 0.9, "selectedModel": "local-default"},
    )
    feature_config = read_json(
        loaded_paths["feature_columns"],
        {"featureColumns": FALLBACK_FEATURE_COLUMNS},
    )
    metadata = read_json(
        loaded_paths["metadata"],
        {"modelVersion": "local-risk-classifier-v1", "modelType": "P1_RiskClassification"},
    )


def feature_columns():
    return feature_config.get("featureColumns") or feature_config.get("feature_columns") or FALLBACK_FEATURE_COLUMNS


def risk_alert_threshold() -> float:
    return float(threshold_config.get("riskAlertThreshold", 0.7))


def risk_critical_threshold() -> float:
    return float(threshold_config.get("riskCriticalThreshold", 0.9))


def risk_level(probability: float) -> str:
    alert_threshold = risk_alert_threshold()
    critical_threshold = risk_critical_threshold()
    if probability >= critical_threshold:
        return "CRITICAL"
    if probability >= alert_threshold:
        return "HIGH"
    if probability >= max(0.5, alert_threshold * 0.7):
        return "MEDIUM"
    return "LOW"


@app.route("/api/risk/score", methods=["POST"])
def score():
    payload = request.get_json(force=True)
    columns = feature_columns()

    missing = [col for col in columns if col not in payload]
    if missing:
        return jsonify({"success": False, "message": "missing feature columns", "missing": missing}), 400

    row = {col: payload[col] for col in columns}
    df = pd.DataFrame([row], columns=columns)

    if hasattr(model, "predict_proba"):
        probability = float(model.predict_proba(df)[0][1])
    else:
        probability = float(model.predict(df)[0])

    threshold = risk_alert_threshold()
    label = 1 if probability >= threshold else 0

    return jsonify(
        {
            "success": True,
            "riskProbability": probability,
            "riskLabel": label,
            "riskLevel": risk_level(probability),
            "threshold": threshold,
            "criticalThreshold": risk_critical_threshold(),
            "modelVersion": metadata.get("modelVersion"),
            "selectedModel": threshold_config.get("selectedModel"),
        }
    )


@app.route("/api/risk/health", methods=["GET"])
def health():
    return jsonify(
        {
            "success": True,
            "modelPath": loaded_paths.get("model"),
            "modelVersion": metadata.get("modelVersion"),
            "threshold": risk_alert_threshold(),
            "criticalThreshold": risk_critical_threshold(),
            "featureColumns": feature_columns(),
        }
    )


@app.route("/api/risk/reload", methods=["POST"])
def reload_model():
    load_model_state()
    return health()


load_model_state()


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=int(os.environ.get("ZEPHYR_MODEL_SERVICE_PORT", "5001")))
