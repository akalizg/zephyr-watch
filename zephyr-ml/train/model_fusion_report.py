import argparse
import csv
import json
import os
from datetime import datetime


CURRENT_DIR = os.path.dirname(os.path.abspath(__file__))
ML_ROOT = os.path.abspath(os.path.join(CURRENT_DIR, ".."))
DATA_DIR = os.path.join(ML_ROOT, "data")
MODEL_DIR = os.path.join(ML_ROOT, "models")
REPORT_DIR = os.path.join(ML_ROOT, "reports")


def read_json(path):
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def write_json(path, payload):
    with open(path, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, indent=2)


def write_model_compare(metrics, output_path):
    fields = ["model", "precision", "recall", "f1", "pr_auc", "roc_auc", "threshold", "tn", "fp", "fn", "tp"]
    with open(output_path, "w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fields)
        writer.writeheader()
        for metric in metrics:
            matrix = metric.get("confusionMatrix", {})
            writer.writerow({
                "model": metric.get("model"),
                "precision": metric.get("precision"),
                "recall": metric.get("recall"),
                "f1": metric.get("f1"),
                "pr_auc": metric.get("pr_auc"),
                "roc_auc": metric.get("roc_auc"),
                "threshold": metric.get("threshold", {}).get("threshold"),
                "tn": matrix.get("tn"),
                "fp": matrix.get("fp"),
                "fn": matrix.get("fn"),
                "tp": matrix.get("tp"),
            })


def write_threshold_compare(metrics, output_path):
    fields = ["model", "threshold", "precision", "recall", "f1"]
    with open(output_path, "w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fields)
        writer.writeheader()
        for metric in metrics:
            threshold = metric.get("threshold", {})
            writer.writerow({
                "model": metric.get("model"),
                "threshold": threshold.get("threshold"),
                "precision": threshold.get("precision", metric.get("precision")),
                "recall": threshold.get("recall", metric.get("recall")),
                "f1": threshold.get("f1", metric.get("f1")),
            })


def infer_label(row, risk_rul_threshold):
    if row.get("riskLabel") not in (None, ""):
        return int(float(row["riskLabel"]))
    rul_value = row.get("RUL", row.get("rul", ""))
    if rul_value == "":
        return None
    return 1 if float(rul_value) <= risk_rul_threshold else 0


def write_class_distribution(input_path, output_path, risk_rul_threshold):
    counts = {}
    total = 0
    with open(input_path, "r", encoding="utf-8", newline="") as f:
        reader = csv.DictReader(f)
        for row in reader:
            label = infer_label(row, risk_rul_threshold)
            if label is None:
                continue
            counts[label] = counts.get(label, 0) + 1
            total += 1
    with open(output_path, "w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=["label", "count", "ratio"])
        writer.writeheader()
        for label in sorted(counts):
            writer.writerow({
                "label": label,
                "count": counts[label],
                "ratio": round(counts[label] / max(total, 1), 6),
            })


def write_best_summary(best, output_path):
    lines = [
        "# Best Model Summary",
        "",
        "- 最优模型名称：`%s`" % best.get("model"),
        "- 选择依据：按 PR-AUC 最高选择，强调高风险少数类识别能力。",
        "- PR-AUC：`%.6f`" % best.get("pr_auc", 0.0),
        "- Precision：`%.6f`" % best.get("precision", 0.0),
        "- Recall：`%.6f`" % best.get("recall", 0.0),
        "- F1：`%.6f`" % best.get("f1", 0.0),
        "- 最优阈值：`%.4f`" % best.get("threshold", {}).get("threshold", 0.5),
        "",
        "## 为什么不主要看 Accuracy",
        "",
        "高风险样本通常是少数类，Accuracy 容易被大量正常样本稀释。当前报告更关注 Precision、Recall、F1 和 PR-AUC，用来衡量告警是否能及时发现高风险且控制误报。",
        "",
        "## 当前模型局限",
        "",
        "- 当前模型仍是表格特征上的传统机器学习基线，没有宣称完成 ONNX、TF Serving 或深度推荐/深度时序模型。",
        "- 阈值来自离线评估，线上仍需结合人工审核反馈持续校准。",
        "- 真实融合指标需要在可用训练环境中运行 `train_baselines.py`，该脚本已支持基于预测概率的 PR-AUC 加权融合。",
    ]
    with open(output_path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")


def write_fusion_files(metrics, model_dir, report_dir):
    if len(metrics) < 2:
        return
    pr_auc_sum = sum(max(float(item.get("pr_auc", 0.0)), 0.0) for item in metrics)
    if pr_auc_sum <= 0:
        return
    weights = {item["model"]: max(float(item.get("pr_auc", 0.0)), 0.0) / pr_auc_sum for item in metrics}
    weighted = {}
    for key in ["precision", "recall", "f1", "pr_auc", "roc_auc"]:
        values = []
        for item in metrics:
            value = item.get(key)
            if value is not None:
                values.append(float(value) * weights[item["model"]])
        weighted[key] = sum(values) if values else None
    threshold = sum(float(item.get("threshold", {}).get("threshold", 0.5)) * weights[item["model"]] for item in metrics)
    write_json(os.path.join(model_dir, "fusion_metadata.json"), {
        "fusionType": "weighted_probability_average",
        "weightRule": "weight = model PR-AUC / sum(PR-AUC of all trained models)",
        "models": list(weights.keys()),
        "weights": weights,
        "threshold": round(threshold, 4),
        "createdAt": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "note": "Run train_baselines.py in a working ML environment to compute probability-level fusion metrics.",
    })
    write_json(os.path.join(report_dir, "fusion_metrics.json"), {
        "model": "weighted_pr_auc_fusion",
        "weights": weights,
        "threshold": round(threshold, 4),
        "precision": weighted["precision"],
        "recall": weighted["recall"],
        "f1": weighted["f1"],
        "pr_auc": weighted["pr_auc"],
        "roc_auc": weighted["roc_auc"],
        "estimatedFromModelMetrics": True,
        "note": "This fallback report is aggregated from existing model metrics because the local Python 3.13 runtime is not executable. The enhanced train_baselines.py computes real probability-level fusion metrics when training runs normally.",
    })


def main():
    parser = argparse.ArgumentParser(description="Generate Zephyr model comparison and fusion report files from existing metrics.")
    parser.add_argument("--metrics", default=os.path.join(REPORT_DIR, "metrics_report.json"))
    parser.add_argument("--input", default=os.path.join(DATA_DIR, "train_dataset.csv"))
    parser.add_argument("--model-dir", default=MODEL_DIR)
    parser.add_argument("--report-dir", default=REPORT_DIR)
    parser.add_argument("--risk-rul-threshold", type=float, default=30.0)
    args = parser.parse_args()

    os.makedirs(args.model_dir, exist_ok=True)
    os.makedirs(args.report_dir, exist_ok=True)
    payload = read_json(args.metrics)
    metrics = sorted(payload.get("metrics", []), key=lambda item: item.get("pr_auc", 0.0), reverse=True)
    if not metrics:
        raise RuntimeError("No model metrics found in %s" % args.metrics)

    write_model_compare(metrics, os.path.join(args.report_dir, "model_compare.csv"))
    write_threshold_compare(metrics, os.path.join(args.report_dir, "threshold_compare.csv"))
    write_class_distribution(args.input, os.path.join(args.report_dir, "class_distribution.csv"), args.risk_rul_threshold)
    write_best_summary(metrics[0], os.path.join(args.report_dir, "best_model_summary.md"))
    write_fusion_files(metrics, args.model_dir, args.report_dir)
    print(json.dumps({"reports": args.report_dir, "models": args.model_dir, "bestModel": metrics[0].get("model")}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
