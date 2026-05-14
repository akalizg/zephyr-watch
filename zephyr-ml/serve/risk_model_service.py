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

# [新增] 尝试导入 SHAP
try:
    import shap

    HAS_SHAP = True
except ImportError:
    HAS_SHAP = False

CURRENT_DIR = os.path.dirname(os.path.abspath(__file__))
ML_ROOT = os.path.abspath(os.path.join(CURRENT_DIR, ".."))
MODEL_DIR = os.path.join(ML_ROOT, "models")
CACHE_DIR = os.environ.get("ZEPHYR_MODEL_CACHE_DIR", os.path.join(ML_ROOT, ".cache", "active_model"))

DEFAULT_MODEL_PATH = os.path.join(MODEL_DIR, "best_risk_model.pkl")
# [新增] 异常检测模型与 SHAP 基准数据路径
DEFAULT_ANOMALY_MODEL_PATH = os.path.join(MODEL_DIR, "best_anomaly_model.pkl")
DEFAULT_SHAP_BACKGROUND_PATH = os.path.join(MODEL_DIR, "shap_background.pkl")

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
# [新增] 全局状态变量
anomaly_model = None
shap_background = None
shap_explainer = None

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
    bucket_and_object = uri[len("minio://"):]
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


def pick(active: Dict[str, Any], camel: str, snake: str, default: Optional[str] = None) -> str:
    value = active.get(camel)
    if value is None:
        value = active.get(snake)
    if not value:
        if default is not None:
            return default
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
        # [新增] 将异常模型和 SHAP 纳入签名以支持热更新
        pick(active, "anomalyModelUri", "anomaly_model_uri", ""),
        pick(active, "shapBackgroundUri", "shap_background_uri", ""),
    ]
    return "|".join(parts)


def resolve_model_files(active: Optional[Dict[str, Any]] = None) -> Dict[str, str]:
    if not LOAD_ACTIVE_MODEL:
        return {
            "model": DEFAULT_MODEL_PATH,
            "anomaly_model": DEFAULT_ANOMALY_MODEL_PATH,  # [新增]
            "shap_background": DEFAULT_SHAP_BACKGROUND_PATH,  # [新增]
            "threshold": DEFAULT_THRESHOLD_PATH,
            "feature_columns": DEFAULT_FEATURE_COLUMNS_PATH,
            "metadata": DEFAULT_METADATA_PATH,
        }

    if active is None:
        active = fetch_active_model()

    resolved = {
        "model": download_from_minio(pick(active, "modelUri", "model_uri"),
                                     os.path.join(CACHE_DIR, "best_risk_model.pkl")),
        "threshold": download_from_minio(pick(active, "thresholdUri", "threshold_uri"),
                                         os.path.join(CACHE_DIR, "threshold.json")),
        "feature_columns": download_from_minio(
            pick(active, "featureColumnsUri", "feature_columns_uri"),
            os.path.join(CACHE_DIR, "feature_columns.json"),
        ),
        "metadata": download_from_minio(pick(active, "metadataUri", "metadata_uri"),
                                        os.path.join(CACHE_DIR, "model_metadata.json")),
    }

    # [新增] 动态拉取异常模型与 SHAP，容忍缺失 (提供默认值为空字符串)
    anomaly_uri = pick(active, "anomalyModelUri", "anomaly_model_uri", "")
    if anomaly_uri:
        resolved["anomaly_model"] = download_from_minio(anomaly_uri, os.path.join(CACHE_DIR, "best_anomaly_model.pkl"))
    else:
        resolved["anomaly_model"] = DEFAULT_ANOMALY_MODEL_PATH

    shap_uri = pick(active, "shapBackgroundUri", "shap_background_uri", "")
    if shap_uri:
        resolved["shap_background"] = download_from_minio(shap_uri, os.path.join(CACHE_DIR, "shap_background.pkl"))
    else:
        resolved["shap_background"] = DEFAULT_SHAP_BACKGROUND_PATH

    return resolved


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
    # [修改] 声明全局变量
    global model, anomaly_model, shap_background, shap_explainer
    global threshold_config, feature_config, metadata, loaded_paths, active_model_signature

    resolved_paths = resolve_model_files(active)
    ensure_model_file(resolved_paths["model"])

    loaded_model = joblib.load(resolved_paths["model"])

    # [新增] 容错加载异常模型与 SHAP 基准
    loaded_anomaly_model = None
    if os.path.exists(resolved_paths.get("anomaly_model", "")):
        loaded_anomaly_model = joblib.load(resolved_paths["anomaly_model"])

    loaded_shap_bg = None
    if os.path.exists(resolved_paths.get("shap_background", "")):
        loaded_shap_bg = joblib.load(resolved_paths["shap_background"])

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
        anomaly_model = loaded_anomaly_model  # [新增]
        shap_background = loaded_shap_bg  # [新增]
        shap_explainer = None  # [新增] 置空以便在接口中懒加载
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
    global shap_explainer  # ✅ 移到函数最开始

    payload = request.get_json(force=True)
    with state_lock:
        current_model = model
        current_anomaly_model = anomaly_model  # [新增]
        current_shap_bg = shap_background  # [新增]
        current_shap_explainer = shap_explainer  # [新增]
        current_metadata = dict(metadata)
        current_threshold_config = dict(threshold_config)
        current_feature_config = dict(feature_config)

    columns = current_feature_config.get("featureColumns") or current_feature_config.get(
        "feature_columns") or FALLBACK_FEATURE_COLUMNS

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

    # [新增] 无监督模型异常侦测兜底逻辑
    anomaly_score = 0.0
    anomaly_label = 0
    if current_anomaly_model:
        try:
            raw_score = current_anomaly_model.decision_function(df)[0]
            anomaly_score = float(-raw_score)  # 翻转得分
            pred = current_anomaly_model.predict(df)[0]
            anomaly_label = 1 if pred == -1 else 0
        except Exception as e:
            LOGGER.warning("Anomaly detection failed: %s", e)

    # [新增] SHAP 逐条在线解释逻辑
    top_contributors = []
    if HAS_SHAP and (current_shap_explainer is not None or current_shap_bg is not None):
        try:
            if current_shap_explainer is None:
                current_shap_explainer = shap.Explainer(current_model.predict, current_shap_bg)
                with state_lock:
                    shap_explainer = current_shap_explainer  # ✅ 移除 global 声明

            shap_values = current_shap_explainer(df)
            vals = shap_values.values[0]
            contributions = [{"feature": col, "contribution": float(val)} for col, val in zip(columns, vals)]
            contributions.sort(key=lambda x: abs(x["contribution"]), reverse=True)
            top_contributors = contributions[:5]
        except Exception as e:
            LOGGER.warning("SHAP explanation failed: %s", e)

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
            # [新增] 返回体包含无监督和可解释性字段
            "anomalyScore": anomaly_score,
            "anomalyLabel": anomaly_label,
            "topContributors": top_contributors
        }
    )


@app.route("/api/risk/health", methods=["GET"])
def health():
    with state_lock:
        current_paths = dict(loaded_paths)
        current_metadata = dict(metadata)
        current_threshold_config = dict(threshold_config)
        current_feature_config = dict(feature_config)

    columns = current_feature_config.get("featureColumns") or current_feature_config.get(
        "feature_columns") or FALLBACK_FEATURE_COLUMNS
    return jsonify(
        {
            "success": True,
            "modelPath": current_paths.get("model"),
            "anomalyModelPath": current_paths.get("anomaly_model"),  # [新增]
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
