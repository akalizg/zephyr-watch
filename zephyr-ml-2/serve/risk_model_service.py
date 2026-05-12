import json
import os
import subprocess
import sys
from typing import Any, Dict, Tuple

import joblib
import numpy as np
import pandas as pd
from flask import Flask, jsonify, request

# 配置基础路径
CURRENT_DIR = os.path.dirname(os.path.abspath(__file__))
ML_ROOT = os.path.abspath(os.path.join(CURRENT_DIR, ".."))
MODEL_DIR = os.path.join(ML_ROOT, "models")

# 默认文件路径
DEFAULT_MODEL_PATH = os.path.join(MODEL_DIR, "best_risk_model.pkl")
DEFAULT_THRESHOLD_PATH = os.path.join(MODEL_DIR, "threshold.json")
DEFAULT_FEATURE_COLUMNS_PATH = os.path.join(MODEL_DIR, "feature_columns.json")
DEFAULT_METADATA_PATH = os.path.join(MODEL_DIR, "model_metadata.json")

# 【加分扩展】定义可选模型矩阵
MODEL_REGISTRY = {
    "logistic_regression": os.path.join(MODEL_DIR, "logistic_regression.pkl"),
    "random_forest": os.path.join(MODEL_DIR, "random_forest.pkl"),
    "mlp": os.path.join(MODEL_DIR, "mlp.pkl"),
    "catboost": os.path.join(MODEL_DIR, "catboost.pkl"),
    "xgboost": os.path.join(MODEL_DIR, "xgboost.pkl"),
    "lightgbm": os.path.join(MODEL_DIR, "lightgbm.pkl"),
    "isolation_forest": os.path.join(MODEL_DIR, "isolation_forest.pkl"),
    "one_class_svm": os.path.join(MODEL_DIR, "one_class_svm.pkl")
}

FALLBACK_FEATURE_COLUMNS = [
    "sampleCount", "cycleStart", "cycleEnd",
    "pressureMin", "pressureMax", "pressureAvg", "pressureStd", "pressureTrend",
    "temperatureMin", "temperatureMax", "temperatureAvg", "temperatureStd", "temperatureTrend",
    "speedMin", "speedMax", "speedAvg", "speedStd", "speedTrend"
]

app = Flask(__name__)

# 全局状态缓存
model_cache = {}
threshold_config = {}
feature_config = {}
metadata = {}


def read_json(path: str, fallback: Dict[str, Any]) -> Dict[str, Any]:
    if not os.path.exists(path):
        return dict(fallback)
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def load_all_configs():
    """初始化加载配置信息"""
    global threshold_config, feature_config, metadata
    threshold_config = read_json(DEFAULT_THRESHOLD_PATH, {"riskAlertThreshold": 0.7, "riskCriticalThreshold": 0.9})
    feature_config = read_json(DEFAULT_FEATURE_COLUMNS_PATH, {"featureColumns": FALLBACK_FEATURE_COLUMNS})
    metadata = read_json(DEFAULT_METADATA_PATH, {"modelVersion": "v1.0-baseline"})


def get_model(model_name: str):
    """根据名称动态获取模型，支持懒加载"""
    if model_name in model_cache:
        return model_cache[model_name]

    # 如果请求了特定模型且文件存在，则加载它
    path = MODEL_REGISTRY.get(model_name)
    if path and os.path.exists(path):
        print(f"[INFO] 正在加载模型实例: {model_name}")
        model_cache[model_name] = joblib.load(path)
        return model_cache[model_name]

    # 否则回退到默认的最优模型
    if "default" not in model_cache:
        model_cache["default"] = joblib.load(DEFAULT_MODEL_PATH)
    return model_cache["default"]


def risk_level(probability: float) -> str:
    alert = float(threshold_config.get("riskAlertThreshold", 0.7))
    critical = float(threshold_config.get("riskCriticalThreshold", 0.9))
    if probability >= critical: return "CRITICAL"
    if probability >= alert: return "HIGH"
    return "NORMAL"


@app.route("/api/risk/score", methods=["POST"])
def score():
    payload = request.get_json(force=True)
    columns = feature_config.get("featureColumns", FALLBACK_FEATURE_COLUMNS)

    # 1. 检查特征是否完整
    missing = [col for col in columns if col not in payload]
    if missing:
        return jsonify({"success": False, "message": "Missing features", "missing": missing}), 400

    # 2. 准备数据
    df = pd.DataFrame([{col: payload[col] for col in columns}])

    # 3. 动态选择模型 (支持从请求中切换模型)
    target_model_name = payload.get("modelType", "default")
    current_model = get_model(target_model_name)

    # 4. 执行推理逻辑
    try:
        if target_model_name in ["isolation_forest", "one_class_svm"]:
            # 无监督模型逻辑：decision_function 输出分数值，越小越异常
            score_raw = current_model.decision_function(df)[0]
            # 简单映射为 0-1 概率感官值
            probability = float(1.0 / (1.0 + np.exp(score_raw)))
        elif hasattr(current_model, "predict_proba"):
            # 标准分类模型逻辑
            probability = float(current_model.predict_proba(df)[0][1])
        else:
            probability = float(current_model.predict(df)[0])
    except Exception as e:
        return jsonify({"success": False, "message": str(e)}), 500

    threshold = float(threshold_config.get("riskAlertThreshold", 0.7))

    return jsonify({
        "success": True,
        "riskProbability": probability,
        "riskLabel": 1 if probability >= threshold else 0,
        "riskLevel": risk_level(probability),
        "activeModel": target_model_name,
        "modelVersion": metadata.get("modelVersion")
    })


@app.route("/api/risk/health", methods=["GET"])
def health():
    return jsonify({
        "status": "UP",
        "loaded_models": list(model_cache.keys()),
        "available_registry": list(MODEL_REGISTRY.keys())
    })


# 执行初始化
load_all_configs()

if __name__ == "__main__":
    # 监听 5001 端口与 Flink 对接
    port = int(os.environ.get("ZEPHYR_MODEL_SERVICE_PORT", 5001))
    app.run(host="0.0.0.0", port=port)