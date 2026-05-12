import argparse
import json
import os
import subprocess
import sys
from typing import Tuple

import pandas as pd
import requests

CURRENT_DIR = os.path.dirname(os.path.abspath(__file__))
ML_ROOT = os.path.abspath(os.path.join(CURRENT_DIR, ".."))
DATA_DIR = os.path.join(ML_ROOT, "data")
REPORT_DIR = os.path.join(ML_ROOT, "reports")


def export_review_labels(api_base: str, output_path: str, limit: int) -> int:
    response = requests.get(
        api_base.rstrip("/") + "/api/learning/review-labels",
        params={"limit": limit},
        timeout=10,
    )
    response.raise_for_status()
    payload = response.json()
    rows = payload.get("data", payload)
    if isinstance(rows, dict):
        rows = rows.get("items", rows.get("rows", []))

    df = pd.DataFrame(rows)
    os.makedirs(os.path.dirname(output_path), exist_ok=True)
    df.to_csv(output_path, index=False)
    return len(df)


def export_feedback_training_samples(api_base: str, output_path: str, limit: int) -> int:
    response = requests.get(
        api_base.rstrip("/") + "/api/learning/feedback-samples",
        params={"limit": limit},
        timeout=10,
    )
    response.raise_for_status()
    payload = response.json()
    rows = payload.get("data", payload)
    if isinstance(rows, dict):
        rows = rows.get("items", rows.get("rows", []))

    df = pd.DataFrame(rows)
    os.makedirs(os.path.dirname(output_path), exist_ok=True)
    df.to_csv(output_path, index=False)
    return len(df)


def normalize_feedback(input_path: str, output_path: str) -> int:
    if not os.path.exists(input_path):
        pd.DataFrame().to_csv(output_path, index=False)
        return 0

    feedback_df = pd.read_csv(input_path)
    if len(feedback_df) == 0:
        feedback_df.to_csv(output_path, index=False)
        return 0

    # 1. 基础重命名映射
    rename_map = {
        "machine_id": "machineId",
        "window_start": "windowStart",
        "window_end": "windowEnd",
        "sample_count": "sampleCount",
        "cycle_start": "cycleStart",
        "cycle_end": "cycleEnd",
        "pressure_min": "pressureMin",
        "pressure_max": "pressureMax",
        "pressure_avg": "pressureAvg",
        "pressure_std": "pressureStd",
        "pressure_trend": "pressureTrend",
        "temperature_min": "temperatureMin",
        "temperature_max": "temperatureMax",
        "temperature_avg": "temperatureAvg",
        "temperature_std": "temperatureStd",
        "temperature_trend": "temperatureTrend",
        "speed_min": "speedMin",
        "speed_max": "speedMax",
        "speed_avg": "speedAvg",
        "speed_std": "speedStd",
        "speed_trend": "speedTrend",
        "RUL": "rul",
        "risk_label": "riskLabel",
        "review_label": "reviewLabel",
    }
    feedback_df = feedback_df.rename(columns=rename_map)

    # 2. 核心：强制对齐 riskLabel 字段，杜绝 NaN
    if "riskLabel" not in feedback_df.columns:
        if "risk_label" in feedback_df.columns:
            feedback_df["riskLabel"] = feedback_df["risk_label"]
        elif "reviewLabel" in feedback_df.columns:
            feedback_df["riskLabel"] = feedback_df["reviewLabel"].map(
                {"TRUE_POSITIVE": 1, "FALSE_POSITIVE": 0, "CONFIRMED_RISK": 1, "NORMAL": 0}
            )

    # 强制填充空标签并转换为整数
    feedback_df["riskLabel"] = feedback_df["riskLabel"].fillna(0).astype(int)

    # 3. 特征完整性检查
    required_features = [
        "sampleCount", "cycleStart", "cycleEnd",
        "pressureMin", "pressureMax", "pressureAvg", "pressureStd", "pressureTrend",
        "temperatureMin", "temperatureMax", "temperatureAvg", "temperatureStd", "temperatureTrend",
        "speedMin", "speedMax", "speedAvg", "speedStd", "speedTrend",
    ]

    # 过滤掉任何特征缺失的行
    feedback_df = feedback_df.dropna(subset=required_features)

    missing = [col for col in required_features if col not in feedback_df.columns]
    if missing:
        # 如果依然缺失列，说明 API 结构有问题，抛出具体列名
        raise ValueError("Feedback samples missing critical columns: %s" % missing)

    feedback_df.to_csv(output_path, index=False)
    return len(feedback_df)


def merge_training_data(base_path: str, feedback_path: str, output_path: str) -> Tuple[int, int]:
    base_df = pd.read_csv(base_path)

    # 强制将原始数据集的 risk_label 统一为驼峰 riskLabel，防止合并产生 NaN
    if "risk_label" in base_df.columns and "riskLabel" not in base_df.columns:
        base_df = base_df.rename(columns={"risk_label": "riskLabel"})

    if not os.path.exists(feedback_path):
        base_df.to_csv(output_path, index=False)
        return len(base_df), 0

    feedback_df = pd.read_csv(feedback_path)
    if len(feedback_df) == 0:
        base_df.to_csv(output_path, index=False)
        return len(base_df), 0

    # 合并数据
    merged_df = pd.concat([base_df, feedback_df], ignore_index=True)

    # 合并后再次强制清理一遍可能产生的 NaN 标签行
    merged_df = merged_df.dropna(subset=["riskLabel"])
    merged_df["riskLabel"] = merged_df["riskLabel"].astype(int)

    # 去重
    dedup_keys = [col for col in ["machineId", "windowStart", "windowEnd"] if col in merged_df.columns]
    if dedup_keys:
        merged_df = merged_df.drop_duplicates(subset=dedup_keys, keep="last")

    merged_df.to_csv(output_path, index=False)
    return len(base_df), len(feedback_df)


def run_command(command):
    print("[INFO] running:", " ".join(command))
    subprocess.check_call(command, cwd=ML_ROOT)


def main():
    parser = argparse.ArgumentParser(
        description="Export review labels, merge feedback samples, retrain and optionally register.")
    parser.add_argument("--api-base", default=os.environ.get("ZEPHYR_API_BASE", "http://localhost:8080"))
    parser.add_argument("--training-input", default=os.path.join(DATA_DIR, "train_dataset.csv"))
    parser.add_argument("--feedback-input", default=os.path.join(DATA_DIR, "feedback_training_sample.csv"))
    parser.add_argument("--skip-export-feedback", action="store_true")
    parser.add_argument("--review-label-output", default=os.path.join(DATA_DIR, "review_labels.csv"))
    parser.add_argument("--normalized-feedback-output",
                        default=os.path.join(DATA_DIR, "normalized_feedback_samples.csv"))
    parser.add_argument("--merged-output", default=os.path.join(DATA_DIR, "merged_train_dataset.csv"))
    parser.add_argument("--min-labels", type=int, default=10)
    parser.add_argument("--review-limit", type=int, default=1000)
    parser.add_argument("--require-all", action="store_true")
    parser.add_argument("--register", action="store_true")
    parser.add_argument("--activate", action="store_true")
    args = parser.parse_args()

    os.makedirs(REPORT_DIR, exist_ok=True)
    exported = export_review_labels(args.api_base, args.review_label_output, args.review_limit)

    exported_feedback = 0
    if not args.skip_export_feedback:
        exported_feedback = export_feedback_training_samples(args.api_base, args.feedback_input, args.review_limit)

    normalized = normalize_feedback(args.feedback_input, args.normalized_feedback_output)

    base_count, feedback_count = merge_training_data(
        args.training_input,
        args.normalized_feedback_output if normalized > 0 else args.feedback_input,
        args.merged_output,
    )

    summary = {
        "exportedReviewLabels": exported,
        "exportedFeedbackTrainingSamples": exported_feedback,
        "normalizedFeedbackSamples": normalized,
        "baseSamples": base_count,
        "mergedFeedbackSamples": feedback_count,
        "mergedOutput": args.merged_output,
    }

    # 如果达到门槛，触发训练
    if exported < args.min_labels and feedback_count < args.min_labels:
        summary["skipped"] = True
        summary["reason"] = f"not enough labels (current: {feedback_count}, need: {args.min_labels})"
    else:
        train_script = os.path.join("train", "train_baselines.py")
        train_command = [sys.executable, train_script, "--input", args.merged_output]
        if args.require_all:
            train_command.append("--require-all")
        run_command(train_command)

        if args.register:
            register_command = [sys.executable, os.path.join("train", "register_model.py")]
            if args.activate:
                register_command.append("--activate")
            run_command(register_command)
        summary["skipped"] = False

    with open(os.path.join(REPORT_DIR, "incremental_retrain_summary.json"), "w", encoding="utf-8") as f:
        json.dump(summary, f, ensure_ascii=False, indent=2)
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()