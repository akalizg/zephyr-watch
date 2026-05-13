import json
import logging
import os
import subprocess
import sys
import threading
import time
from typing import Any, Dict, Optional, Tuple

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
AUTO_RELOAD_ACTIVE_MODEL = os.environ.get("ZEPHYR_AUTO_RELOAD_ACTIVE_MODEL", "true").lower() == "true"
ACTIVE_MODEL_POLL_SEC = float(os.environ.get("ZEPHYR_ACTIVE_MODEL_POLL_SEC", "15"))
ACTIVE_MODEL_REQUEST_TIMEOUT_SEC = float(os.environ.get("ZEPHYR_ACTIVE_MODEL_REQUEST_TIMEOUT_SEC", "5"))

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
LOGGER = logging.getLogger(__name__)

model = None
threshold_config: Dict[str, Any] = {}
feature_config: Dict[str, Any] = {}
metadata: Dict[str, Any] = {}
loaded_paths: Dict[str, str] = {}
active_model_signature = ""
state_lock = threading.RLock()


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


def fetch_active_model() -> Dict[str, Any]:
    if requests is None:
        raise RuntimeError("requests package is required when ZEPHYR_LOAD_ACTIVE_MODEL=true")

    response = requests.get(MODEL_REGISTRY_ACTIVE_URL, timeout=ACTIVE_MODEL_REQUEST_TIMEOUT_SEC)
    response.raise_for_status()
    return unwrap_active_response(response.json())


def build_active_model_signature(active: Dict[str, Any]) -> str:
    parts = [
        pick(active, "modelVersion", "model_version"),
        pick(active, "modelUri", "model_uri"),
        pick(active, "thresholdUri", "threshold_uri"),
        pick(active, "featureColumnsUri", "feature_columns_uri"),
        pick(active, "metadataUri", "metadata_uri"),
    ]
    return "|".join(parts)


def resolve_model_files(active: Optional[Dict[str, Any]] = None) -> Dict[str, str]:
    if not LOAD_ACTIVE_MODEL:
        return {
            "model": DEFAULT_MODEL_PATH,
            "threshold": DEFAULT_THRESHOLD_PATH,
            "feature_columns": DEFAULT_FEATURE_COLUMNS_PATH,
            "metadata": DEFAULT_METADATA_PATH,
        }

    if active is None:
        active = fetch_active_model()

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


def load_model_state(active: Optional[Dict[str, Any]] = None) -> None:
    global model, threshold_config, feature_config, metadata, loaded_paths, active_model_signature

    resolved_paths = resolve_model_files(active)
    ensure_model_file(resolved_paths["model"])
    loaded_model = joblib.load(resolved_paths["model"])
    loaded_threshold = read_json(
        resolved_paths["threshold"],
        {"riskAlertThreshold": 0.7, "riskCriticalThreshold": 0.9, "selectedModel": "local-default"},
    )
    loaded_feature_config = read_json(
        resolved_paths["feature_columns"],
        {"featureColumns": FALLBACK_FEATURE_COLUMNS},
    )
    loaded_metadata = read_json(
        resolved_paths["metadata"],
        {"modelVersion": "local-risk-classifier-v1", "modelType": "P1_RiskClassification"},
    )

    with state_lock:
        model = loaded_model
        threshold_config = loaded_threshold
        feature_config = loaded_feature_config
        metadata = loaded_metadata
        loaded_paths = resolved_paths
        if active is not None:
            active_model_signature = build_active_model_signature(active)
        else:
            active_model_signature = ""


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
    with state_lock:
        current_model = model
        current_metadata = dict(metadata)
        current_threshold_config = dict(threshold_config)
        current_feature_config = dict(feature_config)

    columns = current_feature_config.get("featureColumns") or current_feature_config.get("feature_columns") or FALLBACK_FEATURE_COLUMNS

    missing = [col for col in columns if col not in payload]
    if missing:
        return jsonify({"success": False, "message": "missing feature columns", "missing": missing}), 400

    row = {col: payload[col] for col in columns}
    df = pd.DataFrame([row], columns=columns)

    if hasattr(current_model, "predict_proba"):
        probability = float(current_model.predict_proba(df)[0][1])
    else:
        probability = float(current_model.predict(df)[0])

    threshold = float(current_threshold_config.get("riskAlertThreshold", 0.7))
    critical_threshold = float(current_threshold_config.get("riskCriticalThreshold", 0.9))
    label = 1 if probability >= threshold else 0

    if probability >= critical_threshold:
        level = "CRITICAL"
    elif probability >= threshold:
        level = "HIGH"
    elif probability >= max(0.5, threshold * 0.7):
        level = "MEDIUM"
    else:
        level = "LOW"

    return jsonify(
        {
            "success": True,
            "riskProbability": probability,
            "riskLabel": label,
            "riskLevel": level,
            "threshold": threshold,
            "criticalThreshold": critical_threshold,
            "modelVersion": current_metadata.get("modelVersion"),
            "selectedModel": current_threshold_config.get("selectedModel"),
        }
    )


@app.route("/api/risk/health", methods=["GET"])
def health():
    with state_lock:
        current_paths = dict(loaded_paths)
        current_metadata = dict(metadata)
        current_threshold_config = dict(threshold_config)
        current_feature_config = dict(feature_config)

    columns = current_feature_config.get("featureColumns") or current_feature_config.get("feature_columns") or FALLBACK_FEATURE_COLUMNS
    return jsonify(
        {
            "success": True,
            "modelPath": current_paths.get("model"),
            "modelVersion": current_metadata.get("modelVersion"),
            "threshold": float(current_threshold_config.get("riskAlertThreshold", 0.7)),
            "criticalThreshold": float(current_threshold_config.get("riskCriticalThreshold", 0.9)),
            "featureColumns": columns,
        }
    )


@app.route("/api/risk/reload", methods=["POST"])
def reload_model():
    load_model_state()
    return health()


def active_model_watch_loop() -> None:
    while True:
        try:
            active = fetch_active_model()
            signature = build_active_model_signature(active)
            if signature != active_model_signature:
                LOGGER.info("Detected active model change. Reloading model state.")
                load_model_state(active)
        except Exception as exc:
            LOGGER.warning("Active model watcher failed: %s", exc)
        time.sleep(ACTIVE_MODEL_POLL_SEC)


load_model_state()

if LOAD_ACTIVE_MODEL and AUTO_RELOAD_ACTIVE_MODEL:
    watcher = threading.Thread(target=active_model_watch_loop, name="active-model-watcher", daemon=True)
    watcher.start()


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=int(os.environ.get("ZEPHYR_MODEL_SERVICE_PORT", "5001")))
