import argparse
import importlib
import json
import os
import sys
from datetime import datetime
from typing import Any, Dict, List, Optional, Tuple

import joblib
import numpy as np
import pandas as pd
import matplotlib.pyplot as plt
import seaborn as sns
from sklearn.model_selection import train_test_split
from sklearn.preprocessing import StandardScaler
from sklearn.pipeline import Pipeline
from sklearn.metrics import (
    precision_score, recall_score, f1_score,
    average_precision_score, confusion_matrix, precision_recall_curve
)
from sklearn.linear_model import LogisticRegression
from sklearn.ensemble import RandomForestClassifier, IsolationForest
from sklearn.neural_network import MLPClassifier
from sklearn.svm import OneClassSVM

# 设置绘图风格
plt.style.use('ggplot')
import matplotlib

matplotlib.use('Agg')  # 防止在无界面服务器上运行时报错

CURRENT_DIR = os.path.dirname(os.path.abspath(__file__))
ML_ROOT = os.path.abspath(os.path.join(CURRENT_DIR, ".."))
DATA_DIR = os.path.join(ML_ROOT, "data")
if DATA_DIR not in sys.path:
    sys.path.append(DATA_DIR)

try:
    from schema import FEATURE_COLUMNS
except ImportError:
    FEATURE_COLUMNS = [
        "sampleCount", "cycleStart", "cycleEnd",
        "pressureMin", "pressureMax", "pressureAvg", "pressureStd", "pressureTrend",
        "temperatureMin", "temperatureMax", "temperatureAvg", "temperatureStd", "temperatureTrend",
        "speedMin", "speedMax", "speedAvg", "speedStd", "speedTrend"
    ]


def optional_import(module_name: str, attr_name: Optional[str] = None):
    try:
        module = importlib.import_module(module_name)
        return getattr(module, attr_name) if attr_name else module
    except Exception:
        return None


def load_dataset(path: str, risk_rul_threshold: float) -> pd.DataFrame:
    df = pd.read_csv(path)
    if "riskLabel" not in df.columns:
        rul_col = "RUL" if "RUL" in df.columns else ("rul" if "rul" in df.columns else None)
        if rul_col:
            df["riskLabel"] = (df[rul_col] <= risk_rul_threshold).astype(int)
        else:
            df["riskLabel"] = 0
    return df


def build_models(random_state: int) -> Dict[str, Any]:
    models = {}
    models["logistic_regression"] = Pipeline([
        ("scaler", StandardScaler()),
        ("classifier", LogisticRegression(max_iter=1000, class_weight="balanced", random_state=random_state))
    ])
    models["random_forest"] = RandomForestClassifier(n_estimators=100, random_state=random_state)
    models["mlp"] = Pipeline([
        ("scaler", StandardScaler()),
        ("classifier", MLPClassifier(hidden_layer_sizes=(64, 32), max_iter=500, random_state=random_state))
    ])

    for lib in ["xgboost", "lightgbm", "catboost"]:
        cls_name = "XGBClassifier" if lib == "xgboost" else (
            "LGBMClassifier" if lib == "lightgbm" else "CatBoostClassifier")
        cls = optional_import(lib, cls_name)
        if cls:
            if lib == "catboost":
                models[lib] = cls(iterations=100, verbose=0, random_seed=random_state)
            else:
                models[lib] = cls(random_state=random_state)
    return models


# [新增] 构建无监督异常检测模型
def build_anomaly_models(random_state: int) -> Dict[str, Any]:
    return {
        "isolation_forest": IsolationForest(random_state=random_state, contamination="auto"),
        "one_class_svm": Pipeline([
            ("scaler", StandardScaler()),
            ("svm", OneClassSVM(nu=0.05, kernel="rbf", gamma="scale"))
        ])
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True)
    parser.add_argument("--artifact-dir", default=os.path.join(ML_ROOT, "models"))
    parser.add_argument("--report-dir", default=os.path.join(ML_ROOT, "reports"))
    args = parser.parse_args()

    os.makedirs(args.artifact_dir, exist_ok=True)
    os.makedirs(args.report_dir, exist_ok=True)

    df = load_dataset(args.input, 30.0)

    # 图表 1: 类别分布饼图
    plt.figure(figsize=(6, 6))
    df['riskLabel'].value_counts().plot.pie(autopct='%1.1f%%', colors=['#66b3ff', '#ff9999'], labels=['Normal', 'Risk'])
    plt.title("Class Distribution")
    plt.savefig(os.path.join(args.report_dir, "class_distribution_pie.png"))
    plt.close()

    train_df, test_df = train_test_split(df, test_size=0.2, random_state=42)
    x_train, y_train = train_df[FEATURE_COLUMNS], train_df["riskLabel"]
    x_test, y_test = test_df[FEATURE_COLUMNS], test_df["riskLabel"]

    metrics_list = []
    all_probs = {}
    best_model_obj = None
    best_auc = -1

    # 1. 训练与评估监督模型
    supervised_models = build_models(42)
    for name, model in supervised_models.items():
        print(f"[INFO] Training {name}...")
        model.fit(x_train, y_train)

        y_prob = model.predict_proba(x_test)[:, 1] if hasattr(model, "predict_proba") else model.predict(x_test)
        all_probs[name] = y_prob
        y_pred = (y_prob >= 0.5).astype(int)

        auc = average_precision_score(y_test, y_prob)
        metrics_list.append({
            "model": name,
            "type": "supervised",  # [修改] 增加类型标识
            "precision": float(precision_score(y_test, y_pred, zero_division=0)),
            "recall": float(recall_score(y_test, y_pred, zero_division=0)),
            "f1": float(f1_score(y_test, y_pred, zero_division=0)),
            "pr_auc": float(auc)
        })

        if auc > best_auc:
            best_auc = auc
            best_model_obj = model
            best_name = name

        joblib.dump(model, os.path.join(args.artifact_dir, f"{name}.pkl"))

    # [新增] 将表现最好的监督模型持久化为标准名称，供线上调用
    if best_model_obj:
        joblib.dump(best_model_obj, os.path.join(args.artifact_dir, "best_risk_model.pkl"))

    # [新增] 2. 训练与评估无监督异常检测模型
    print("-" * 30)
    anomaly_models = build_anomaly_models(42)
    best_anomaly_auc = -1
    best_anomaly_model_obj = None

    for name, model in anomaly_models.items():
        print(f"[INFO] Training Anomaly Model: {name}...")
        model.fit(x_train)  # 无监督仅利用特征

        # 异常得分：值越大越异常
        y_scores = -model.decision_function(x_test)
        auc = average_precision_score(y_test, y_scores)

        metrics_list.append({
            "model": name,
            "type": "unsupervised",
            "precision": 0.0, "recall": 0.0, "f1": 0.0,
            "pr_auc": float(auc)
        })

        if auc > best_anomaly_auc:
            best_anomaly_auc = auc
            best_anomaly_model_obj = model

        joblib.dump(model, os.path.join(args.artifact_dir, f"{name}.pkl"))

    if best_anomaly_model_obj:
        joblib.dump(best_anomaly_model_obj, os.path.join(args.artifact_dir, "best_anomaly_model.pkl"))

    # [新增] 持久化 SHAP Background 数据供在线接口初始化解释器使用
    print("[INFO] Saving SHAP background dataset...")
    background_data = x_train.sample(min(100, len(x_train)), random_state=42)
    joblib.dump(background_data, os.path.join(args.artifact_dir, "shap_background.pkl"))

    # 图表 2: 各模型 PR 曲线对比图
    plt.figure(figsize=(10, 6))
    for name, y_prob in all_probs.items():
        p, r, _ = precision_recall_curve(y_test, y_prob)
        plt.plot(r, p, label=f'{name} (AUC={average_precision_score(y_test, y_prob):.2f})')
    plt.xlabel('Recall')
    plt.ylabel('Precision')
    plt.title('PR Curve Comparison')
    plt.legend()
    plt.savefig(os.path.join(args.report_dir, "pr_curve_compare.png"))
    plt.close()

    # 图表 3: 混淆矩阵热力图 (使用最佳模型)
    best_y_pred = (all_probs[best_name] >= 0.5).astype(int)
    cm = confusion_matrix(y_test, best_y_pred)
    plt.figure(figsize=(8, 6))
    sns.heatmap(cm, annot=True, fmt='d', cmap='Blues')
    plt.title(f'Confusion Matrix - {best_name}')
    plt.savefig(os.path.join(args.report_dir, "confusion_matrix.png"))
    plt.close()

    # 图表 4: XGBoost 特征重要性图 (如果可用)
    if "xgboost" in supervised_models:
        xgb_model = supervised_models["xgboost"]
        importances = xgb_model.feature_importances_
        indices = np.argsort(importances)[-10:]  # 前10个
        plt.figure(figsize=(10, 6))
        plt.title('XGBoost Top 10 Feature Importance')
        plt.barh(range(len(indices)), importances[indices], align='center')
        plt.yticks(range(len(indices)), [FEATURE_COLUMNS[i] for i in indices])
        plt.savefig(os.path.join(args.report_dir, "xgb_feature_importance.png"))
        plt.close()

    # 图表 5: Precision-Recall 阈值权衡曲线
    p, r, thresholds = precision_recall_curve(y_test, all_probs[best_name])
    plt.figure(figsize=(10, 6))
    plt.plot(thresholds, p[:-1], "b--", label="Precision")
    plt.plot(thresholds, r[:-1], "g-", label="Recall")
    plt.title(f"Threshold PR Tradeoff - {best_name}")
    plt.xlabel("Threshold")
    plt.legend()
    plt.savefig(os.path.join(args.report_dir, "threshold_pr_curve.png"))
    plt.close()

    # 图表 6: SHAP 解释图 (对最佳模型进行采样解释)
    shap = optional_import("shap")
    if shap:
        print("[INFO] Generating SHAP summary...")
        try:
            # 简化版 SHAP (采样 100 条防止计算过慢)，[修改] 使用固定的 background_data
            explainer = shap.Explainer(best_model_obj.predict, background_data)
            shap_values = explainer(x_test.sample(min(100, len(x_test))))
            plt.figure()
            shap.summary_plot(shap_values, x_test.sample(min(100, len(x_test))), show=False)
            plt.savefig(os.path.join(args.report_dir, "shap_summary.png"), bbox_inches='tight')
            plt.close()
        except Exception as e:
            print(f"[WARN] SHAP generation failed: {e}")

    # 保存报告
    report_path = os.path.join(args.report_dir, "metrics_report.csv")
    df_report = pd.DataFrame(metrics_list).sort_values(by="pr_auc", ascending=False)
    df_report.to_csv(report_path, index=False)

    print("-" * 30)
    print(f"[SUCCESS] 报告与所有可视化图表已生成在: {args.report_dir}")


if __name__ == "__main__":
    main()
