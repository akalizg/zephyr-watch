import argparse
import json
import os
import sys
from datetime import datetime
from typing import Dict, Any

import joblib
import pandas as pd
from sklearn.ensemble import RandomForestRegressor
from sklearn.model_selection import GroupShuffleSplit
from sklearn2pmml.pipeline import PMMLPipeline
from sklearn2pmml import sklearn2pmml

CURRENT_DIR = os.path.dirname(os.path.abspath(__file__))
DATA_DIR = os.path.abspath(os.path.join(CURRENT_DIR, "..", "data"))
if DATA_DIR not in sys.path:
    sys.path.append(DATA_DIR)

from schema import FEATURE_COLUMNS  # noqa: E402

def split_by_machine(df: pd.DataFrame, test_size: float, random_state: int):
    splitter = GroupShuffleSplit(n_splits=1, test_size=test_size, random_state=random_state)
    groups = df["machineId"]

    for train_idx, test_idx in splitter.split(df, groups=groups):
        train_df = df.iloc[train_idx].reset_index(drop=True)
        test_df = df.iloc[test_idx].reset_index(drop=True)
        return train_df, test_df

    raise RuntimeError("训练集/测试集划分失败")

def build_metadata(args, train_df: pd.DataFrame, test_df: pd.DataFrame) -> Dict[str, Any]:
    return {
        "modelType": "RandomForestRegressor_PMML",
        "createdAt": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "featureColumns": FEATURE_COLUMNS,
        "targetColumn": "RUL",
        "trainSamples": int(len(train_df)),
        "testSamples": int(len(test_df)),
        "trainMachines": int(train_df["machineId"].nunique()),
        "testMachines": int(test_df["machineId"].nunique()),
        "randomState": int(args.random_state),
        "nEstimators": int(args.n_estimators),
        "maxDepth": None if args.max_depth <= 0 else int(args.max_depth)
    }

def main():
    parser = argparse.ArgumentParser(description="训练 Zephyr-Watch 随机森林回归模型 (PMML工业版)")
    parser.add_argument("--input", required=True, help="build_dataset.py 生成的 CSV 文件")
    parser.add_argument("--artifact-dir", required=True, help="模型输出目录")
    parser.add_argument("--test-size", type=float, default=0.2, help="测试集比例")
    parser.add_argument("--random-state", type=int, default=42, help="随机种子")
    parser.add_argument("--n-estimators", type=int, default=300, help="树数量")
    parser.add_argument("--max-depth", type=int, default=10, help="最大深度，<=0 表示不限制")
    args = parser.parse_args()

    df = pd.read_csv(args.input)

    # 核心修改：检查的目标列从 riskLabel 变成了真实的 RUL 数值
    missing = [c for c in FEATURE_COLUMNS + ["machineId", "RUL"] if c not in df.columns]
    if missing:
        raise ValueError(f"训练数据缺少字段: {missing}")

    train_df, test_df = split_by_machine(df, args.test_size, args.random_state)

    X_train = train_df[FEATURE_COLUMNS]
    y_train = train_df["RUL"]

    X_test = test_df[FEATURE_COLUMNS]
    y_test = test_df["RUL"]

    max_depth = None if args.max_depth <= 0 else args.max_depth

    # 核心修改：使用 PMMLPipeline 包装 RandomForestRegressor
    pipeline = PMMLPipeline([
        ("regressor", RandomForestRegressor(
            n_estimators=args.n_estimators,
            max_depth=max_depth,
            random_state=args.random_state,
            n_jobs=-1
        ))
    ])

    print("[INFO] 开始训练回归模型...")
    pipeline.fit(X_train, y_train)

    # 简单评估一下 R^2 决定系数分数
    score = pipeline.score(X_test, y_test)
    print(f"[INFO] 模型在测试集上的 R^2 得分 (越接近1越好): {score:.4f}")

    os.makedirs(args.artifact_dir, exist_ok=True)

    # 核心修改：将模型导出为跨语言的 PMML 格式
    pmml_path = os.path.join(args.artifact_dir, "model.pmml")
    sklearn2pmml(pipeline, pmml_path, with_repr=True)

    # 保留原有的 pkl 格式作为备份
    pkl_path = os.path.join(args.artifact_dir, "model.pkl")
    joblib.dump(pipeline, pkl_path)

    # 写入基础元数据
    metadata_path = os.path.join(args.artifact_dir, "metadata.json")
    metadata = build_metadata(args, train_df, test_df)
    metadata["r2_score"] = score
    with open(metadata_path, "w", encoding="utf-8") as f:
        json.dump(metadata, f, ensure_ascii=False, indent=2)

    print("[INFO] 模型训练与 PMML 导出全部完成！")
    print(f"[INFO] 关键 PMML 文件: {pmml_path} (稍后将由 Flink 加载)")
    print(f"[INFO] 元数据文件: {metadata_path}")

if __name__ == "__main__":
    main()