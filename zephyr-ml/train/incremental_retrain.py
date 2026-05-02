import argparse
import csv
import json
import os
import subprocess
import sys
import urllib.request
from datetime import datetime

CURRENT_DIR = os.path.dirname(os.path.abspath(__file__))
ML_ROOT = os.path.abspath(os.path.join(CURRENT_DIR, ".."))
DATA_DIR = os.path.join(ML_ROOT, "data")
REPORT_DIR = os.path.join(ML_ROOT, "reports")


def fetch_json(url: str):
    with urllib.request.urlopen(url, timeout=20) as response:
        return json.loads(response.read().decode("utf-8"))


def normalize_label(value: str) -> int:
    text = (value or "").strip().lower()
    if text in ("1", "true", "risk", "fault", "failure", "positive", "high", "critical", "confirmed"):
        return 1
    if text in ("0", "false", "normal", "negative", "safe", "benign", "ignored"):
        return 0
    raise ValueError("Unsupported review_label: %s" % value)


def write_review_csv(rows, output_path: str) -> int:
    os.makedirs(os.path.dirname(output_path), exist_ok=True)
    fieldnames = [
        "reviewId",
        "alertId",
        "machineId",
        "eventTime",
        "riskProbability",
        "rul",
        "riskLevel",
        "reviewLabel",
        "riskLabel",
        "reviewer",
        "reviewedAt",
        "modelVersion",
    ]
    written = 0
    with open(output_path, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        for row in rows:
            try:
                risk_label = normalize_label(row.get("review_label"))
            except ValueError:
                continue
            writer.writerow({
                "reviewId": row.get("review_id"),
                "alertId": row.get("alert_id"),
                "machineId": row.get("machine_id"),
                "eventTime": row.get("event_time"),
                "riskProbability": row.get("risk_probability"),
                "rul": row.get("rul"),
                "riskLevel": row.get("risk_level"),
                "reviewLabel": row.get("review_label"),
                "riskLabel": risk_label,
                "reviewer": row.get("reviewer"),
                "reviewedAt": row.get("reviewed_at"),
                "modelVersion": row.get("model_version"),
            })
            written += 1
    return written


def run_command(args):
    print("[INFO] Running: %s" % " ".join(args))
    subprocess.check_call(args)


def main() -> None:
    parser = argparse.ArgumentParser(description="Export reviewed alert labels and retrain Zephyr risk baselines.")
    parser.add_argument("--api-base", default="http://localhost:8080")
    parser.add_argument("--limit", type=int, default=500)
    parser.add_argument("--min-labels", type=int, default=1)
    parser.add_argument("--training-input", default=os.path.join(DATA_DIR, "train_dataset.csv"))
    parser.add_argument("--review-output", default=os.path.join(DATA_DIR, "review_labels.csv"))
    parser.add_argument("--register", action="store_true")
    parser.add_argument("--activate", action="store_true")
    parser.add_argument("--require-all", action="store_true")
    args = parser.parse_args()

    labels_url = "%s/api/learning/review-labels?limit=%d" % (args.api_base.rstrip("/"), args.limit)
    payload = fetch_json(labels_url)
    rows = payload.get("data") or []
    label_count = write_review_csv(rows, args.review_output)
    print("[INFO] Exported %d usable review labels to %s" % (label_count, args.review_output))

    if label_count < args.min_labels:
        print("[WARN] Not enough review labels for scheduled retrain. Required=%d, actual=%d" % (
            args.min_labels, label_count
        ))
        return

    train_args = [
        sys.executable,
        os.path.join(CURRENT_DIR, "train_baselines.py"),
        "--input",
        args.training_input,
    ]
    if args.require_all:
        train_args.append("--require-all")
    run_command(train_args)

    if args.register or args.activate:
        register_args = [
            sys.executable,
            os.path.join(CURRENT_DIR, "register_model.py"),
            "--api-base",
            args.api_base,
        ]
        if args.activate:
            register_args.append("--activate")
        run_command(register_args)

    summary_path = os.path.join(REPORT_DIR, "incremental_retrain_summary.json")
    os.makedirs(REPORT_DIR, exist_ok=True)
    with open(summary_path, "w", encoding="utf-8") as f:
        json.dump({
            "createdAt": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
            "labelCount": label_count,
            "reviewOutput": args.review_output,
            "trainingInput": args.training_input,
            "registered": bool(args.register or args.activate),
            "activated": bool(args.activate),
        }, f, ensure_ascii=False, indent=2)
    print("[INFO] Summary written to %s" % summary_path)


if __name__ == "__main__":
    main()
