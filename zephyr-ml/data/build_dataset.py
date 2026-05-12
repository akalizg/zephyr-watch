import argparse
import json
import os
from glob import glob
from typing import List, Dict, Any

import pandas as pd

from schema import FEATURE_COLUMNS, META_COLUMNS


def load_json_lines_from_path(path: str) -> List[Dict[str, Any]]:
    records: List[Dict[str, Any]] = []

    if os.path.isfile(path):
        files = [path]
    else:
        files = []
        for pattern in ("*.json", "*.txt", "*.log", "*"):
            files.extend(glob(os.path.join(path, pattern)))
        files = sorted(set([f for f in files if os.path.isfile(f)]))

    for file_path in files:
        with open(file_path, "r", encoding="utf-8") as f:
            for line_no, line in enumerate(f, start=1):
                text = line.strip()
                if not text:
                    continue
                try:
                    obj = json.loads(text)
                    if isinstance(obj, dict):
                        records.append(obj)
                except json.JSONDecodeError:
                    print(f"[WARN] 跳过非法 JSON 行: {file_path}:{line_no}")

    return records


def validate_columns(df: pd.DataFrame) -> None:
    required = ["machineId", "cycleEnd"] + FEATURE_COLUMNS
    missing = [c for c in required if c not in df.columns]
    if missing:
        raise ValueError(f"缺少必要字段: {missing}")


def build_rul_labels(df: pd.DataFrame, risk_threshold: int) -> pd.DataFrame:
    # 对每台机器，取窗口级别的最大 cycleEnd，近似作为寿命终点
    max_cycle_df = (
        df.groupby("machineId", as_index=False)["cycleEnd"]
        .max()
        .rename(columns={"cycleEnd": "maxCycle"})
    )

    df = df.merge(max_cycle_df, on="machineId", how="left")
    df["rul"] = df["maxCycle"] - df["cycleEnd"]
    df["riskLabel"] = (df["rul"] <= risk_threshold).astype(int)

    return df


def clean_and_sort(df: pd.DataFrame) -> pd.DataFrame:
    df = df.copy()

    # 保留核心列
    keep_cols = list(dict.fromkeys(META_COLUMNS + FEATURE_COLUMNS + ["machineId", "cycleEnd"]))
    keep_cols = [c for c in keep_cols if c in df.columns]
    df = df[keep_cols]

    # 去重
    subset_cols = [c for c in ["machineId", "windowStart", "windowEnd", "cycleEnd"] if c in df.columns]
    if subset_cols:
        df = df.drop_duplicates(subset=subset_cols)

    # 排序
    sort_cols = [c for c in ["machineId", "cycleEnd", "windowStart"] if c in df.columns]
    if sort_cols:
        df = df.sort_values(sort_cols).reset_index(drop=True)

    return df


def main():
    parser = argparse.ArgumentParser(description="构建 Zephyr-Watch 训练数据集")
    parser.add_argument("--input", required=True, help="阶段二输出目录或单个 JSON 行文件")
    parser.add_argument("--output", required=True, help="输出 CSV 文件路径")
    parser.add_argument("--risk-threshold", type=int, default=30, help="RUL 小于等于该阈值记为高风险")
    args = parser.parse_args()

    records = load_json_lines_from_path(args.input)
    if not records:
        raise ValueError("未读取到任何特征记录，请检查输入路径")

    df = pd.DataFrame(records)
    df = clean_and_sort(df)
    validate_columns(df)
    df = build_rul_labels(df, args.risk_threshold)

    output_dir = os.path.dirname(args.output)
    if output_dir:
        os.makedirs(output_dir, exist_ok=True)

    df.to_csv(args.output, index=False, encoding="utf-8")
    print(f"[INFO] 训练数据集已生成: {args.output}")
    print(f"[INFO] 样本数: {len(df)}")
    print(f"[INFO] 机器数: {df['machineId'].nunique()}")
    print(f"[INFO] 正样本(高风险)数: {int(df['riskLabel'].sum())}")
    print(f"[INFO] 负样本(低风险)数: {int((1 - df['riskLabel']).sum())}")


if __name__ == "__main__":
    main()