import argparse
import json
import os
import sys
from datetime import datetime
from typing import Dict, Any

import joblib
import pandas as pd
from sklearn.ensemble import RandomForestClassifier
from sklearn.model_selection import GroupShuffleSplit

CURRENT_DIR = os.path.dirname(os.path.abspath(__file__))
DATA_DIR = os.path.abspath(os.path.join(CURRENT_DIR, "..", "data"))
if DATA_DIR not in sys.path:
    sys.path.append(DATA_DIR)

from schema import FEATURE_COLUMNS  # noqa: E402
from evaluate import evaluate_classification  # noqa: E402


def split_by_machine(df: pd.DataFrame, test_size: float, random_state: int):
    splitter = GroupShuffleSplit(n_splits=1, test_size=test_size, random_state=random_state)
    groups = df["machineId"]

    for train_idx, test_idx in splitter.split(df, groups=groups):
        train_df = df.iloc[train_idx].reset_index(drop=True)
        test_df = df.iloc[test_idx].reset_index(drop=True)
        return train_df, test_df

    raise RuntimeError("训练集/测试集划分失败")


def build_metadata(metrics: Dict[str, Any], args, train_df: pd.DataFrame, test_df: pd.DataFrame) -> Dict[str, Any]:
    return {
        "modelType": "RandomForestClassifier",
        "createdAt": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "featureColumns": FEATURE_COLUMNS,
        "targetColumn": "riskLabel",
        "trainSamples": int(len(train_df)),
        "testSamples": int(len(test_df)),
        "trainMachines": int(train_df["machineId"].nunique()),
        "testMachines": int(test_df["machineId"].nunique()),
        "riskThreshold": int(args.risk_threshold),
        "randomState": int(args.random_state),
        "nEstimators": int(args.n_estimators),
        "maxDepth": None if args.max_depth <= 0 else int(args.max_depth),
        "metrics": metrics,
    }


def main():
    parser = argparse.ArgumentParser(description="训练 Zephyr-Watch 随机森林基线模型")
    parser.add_argument("--input", required=True, help="build_dataset.py 生成的 CSV 文件")
    parser.add_argument("--artifact-dir", required=True, help="模型输出目录")
    parser.add_argument("--test-size", type=float, default=0.2, help="测试集比例")
    parser.add_argument("--random-state", type=int, default=42, help="随机种子")
    parser.add_argument("--n-estimators", type=int, default=300, help="树数量")
    parser.add_argument("--max-depth", type=int, default=10, help="最大深度，<=0 表示不限制")
    parser.add_argument("--risk-threshold", type=int, default=30, help="元数据记录用")
    args = parser.parse_args()

    df = pd.read_csv(args.input)
    missing = [c for c in FEATURE_COLUMNS + ["machineId", "riskLabel"] if c not in df.columns]
    if missing:
        raise ValueError(f"训练数据缺少字段: {missing}")

    train_df, test_df = split_by_machine(df, args.test_size, args.random_state)

    X_train = train_df[FEATURE_COLUMNS]
    y_train = train_df["riskLabel"]

    X_test = test_df[FEATURE_COLUMNS]
    y_test = test_df["riskLabel"]

    max_depth = None if args.max_depth <= 0 else args.max_depth

    model = RandomForestClassifier(
        n_estimators=args.n_estimators,
        max_depth=max_depth,
        random_state=args.random_state,
        n_jobs=-1,
        class_weight="balanced",
    )
    model.fit(X_train, y_train)

    y_pred = model.predict(X_test)
    y_prob = model.predict_proba(X_test)[:, 1]

    metrics = evaluate_classification(y_test, y_pred, y_prob)

    os.makedirs(args.artifact_dir, exist_ok=True)

    model_path = os.path.join(args.artifact_dir, "model.pkl")
    metadata_path = os.path.join(args.artifact_dir, "metadata.json")
    feature_importance_path = os.path.join(args.artifact_dir, "feature_importance.json")

    joblib.dump(model, model_path)

    metadata = build_metadata(metrics, args, train_df, test_df)
    with open(metadata_path, "w", encoding="utf-8") as f:
        json.dump(metadata, f, ensure_ascii=False, indent=2)

    feature_importance = {
        feature: float(score)
        for feature, score in zip(FEATURE_COLUMNS, model.feature_importances_)
    }
    feature_importance = dict(sorted(feature_importance.items(), key=lambda x: x[1], reverse=True))
    with open(feature_importance_path, "w", encoding="utf-8") as f:
        json.dump(feature_importance, f, ensure_ascii=False, indent=2)

    print("[INFO] 模型训练完成")
    print(f"[INFO] 模型文件: {model_path}")
    print(f"[INFO] 元数据文件: {metadata_path}")
    print(f"[INFO] 特征重要性文件: {feature_importance_path}")
    print("[INFO] 评估结果:")
    for k, v in metrics.items():
        print(f"  - {k}: {v:.6f}" if v is not None else f"  - {k}: None")


if __name__ == "__main__":
    main()