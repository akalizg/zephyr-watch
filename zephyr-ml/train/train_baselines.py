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

CURRENT_DIR = os.path.dirname(os.path.abspath(__file__))
ML_ROOT = os.path.abspath(os.path.join(CURRENT_DIR, ".."))
DATA_DIR = os.path.join(ML_ROOT, "data")
if DATA_DIR not in sys.path:
    sys.path.append(DATA_DIR)

from schema import FEATURE_COLUMNS  # noqa: E402


def import_required_sklearn():
    from sklearn.ensemble import RandomForestClassifier
    from sklearn.linear_model import LogisticRegression
    from sklearn.metrics import (
        average_precision_score,
        confusion_matrix,
        f1_score,
        precision_recall_curve,
        precision_score,
        recall_score,
        roc_auc_score,
    )
    from sklearn.model_selection import GroupShuffleSplit, train_test_split
    from sklearn.pipeline import Pipeline
    from sklearn.preprocessing import StandardScaler

    return {
        "RandomForestClassifier": RandomForestClassifier,
        "LogisticRegression": LogisticRegression,
        "average_precision_score": average_precision_score,
        "confusion_matrix": confusion_matrix,
        "f1_score": f1_score,
        "precision_recall_curve": precision_recall_curve,
        "precision_score": precision_score,
        "recall_score": recall_score,
        "roc_auc_score": roc_auc_score,
        "GroupShuffleSplit": GroupShuffleSplit,
        "train_test_split": train_test_split,
        "Pipeline": Pipeline,
        "StandardScaler": StandardScaler,
    }


def optional_import(module_name: str, attr_name: Optional[str] = None):
    try:
        module = importlib.import_module(module_name)
        return getattr(module, attr_name) if attr_name else module
    except Exception:
        return None


def load_dataset(path: str, risk_rul_threshold: float) -> pd.DataFrame:
    df = pd.read_csv(path)
    target_col = "riskLabel" if "riskLabel" in df.columns else None
    rul_col = "RUL" if "RUL" in df.columns else ("rul" if "rul" in df.columns else None)
    if target_col is None:
        if rul_col is None:
            raise ValueError("Training data must contain riskLabel or RUL/rul.")
        df["riskLabel"] = (df[rul_col] <= risk_rul_threshold).astype(int)

    missing = [col for col in FEATURE_COLUMNS if col not in df.columns]
    if missing:
        raise ValueError("Training data is missing feature columns: %s" % missing)

    if "machineId" not in df.columns:
        df["machineId"] = 0

    return df


def split_dataset(df: pd.DataFrame, test_size: float, random_state: int, sk: Dict[str, Any]):
    groups = df["machineId"]
    if groups.nunique() > 1:
        splitter = sk["GroupShuffleSplit"](n_splits=1, test_size=test_size, random_state=random_state)
        train_idx, test_idx = next(splitter.split(df, groups=groups))
        return df.iloc[train_idx].reset_index(drop=True), df.iloc[test_idx].reset_index(drop=True)

    train_df, test_df = sk["train_test_split"](
        df,
        test_size=test_size,
        random_state=random_state,
        stratify=df["riskLabel"] if df["riskLabel"].nunique() > 1 else None,
    )
    return train_df.reset_index(drop=True), test_df.reset_index(drop=True)


def class_weight(y: pd.Series) -> float:
    positives = max(int((y == 1).sum()), 1)
    negatives = max(int((y == 0).sum()), 1)
    return negatives / positives


def build_models(random_state: int, y_train: pd.Series, sk: Dict[str, Any]) -> Dict[str, Any]:
    models: Dict[str, Any] = {}
    Pipeline = sk["Pipeline"]
    StandardScaler = sk["StandardScaler"]
    LogisticRegression = sk["LogisticRegression"]
    RandomForestClassifier = sk["RandomForestClassifier"]

    models["logistic_regression"] = Pipeline([
        ("scaler", StandardScaler()),
        ("classifier", LogisticRegression(
            max_iter=1000,
            class_weight="balanced",
            random_state=random_state,
        )),
    ])
    models["random_forest"] = RandomForestClassifier(
        n_estimators=300,
        max_depth=12,
        class_weight="balanced",
        random_state=random_state,
        n_jobs=-1,
    )

    XGBClassifier = optional_import("xgboost", "XGBClassifier")
    if XGBClassifier is not None:
        models["xgboost"] = XGBClassifier(
            n_estimators=300,
            max_depth=5,
            learning_rate=0.05,
            subsample=0.9,
            colsample_bytree=0.9,
            eval_metric="logloss",
            scale_pos_weight=class_weight(y_train),
            random_state=random_state,
            n_jobs=-1,
        )

    LGBMClassifier = optional_import("lightgbm", "LGBMClassifier")
    if LGBMClassifier is not None:
        models["lightgbm"] = LGBMClassifier(
            n_estimators=300,
            max_depth=-1,
            learning_rate=0.05,
            subsample=0.9,
            colsample_bytree=0.9,
            class_weight="balanced",
            random_state=random_state,
            n_jobs=-1,
            verbose=-1,
        )

    return models


def predict_probability(model: Any, x_test: pd.DataFrame) -> np.ndarray:
    if hasattr(model, "predict_proba"):
        return model.predict_proba(x_test)[:, 1]
    if hasattr(model, "decision_function"):
        score = model.decision_function(x_test)
        return 1.0 / (1.0 + np.exp(-score))
    raise ValueError("Model does not expose predict_proba or decision_function.")


def find_best_threshold(y_true: np.ndarray, y_prob: np.ndarray, sk: Dict[str, Any],
                        min_recall: float) -> Dict[str, float]:
    best = {
        "threshold": 0.5,
        "f1": -1.0,
        "precision": 0.0,
        "recall": 0.0,
    }
    for threshold in np.linspace(0.01, 0.99, 99):
        y_pred = (y_prob >= threshold).astype(int)
        precision = float(sk["precision_score"](y_true, y_pred, zero_division=0))
        recall = float(sk["recall_score"](y_true, y_pred, zero_division=0))
        f1 = float(sk["f1_score"](y_true, y_pred, zero_division=0))
        if recall >= min_recall and f1 > best["f1"]:
            best = {
                "threshold": float(round(threshold, 4)),
                "f1": f1,
                "precision": precision,
                "recall": recall,
            }

    if best["f1"] < 0:
        y_pred = (y_prob >= 0.5).astype(int)
        best = {
            "threshold": 0.5,
            "f1": float(sk["f1_score"](y_true, y_pred, zero_division=0)),
            "precision": float(sk["precision_score"](y_true, y_pred, zero_division=0)),
            "recall": float(sk["recall_score"](y_true, y_pred, zero_division=0)),
        }
    return best


def evaluate_model(name: str, model: Any, x_test: pd.DataFrame, y_test: np.ndarray,
                   sk: Dict[str, Any], min_recall: float) -> Tuple[Dict[str, Any], np.ndarray]:
    y_prob = predict_probability(model, x_test)
    threshold = find_best_threshold(y_test, y_prob, sk, min_recall)
    y_pred = (y_prob >= threshold["threshold"]).astype(int)
    tn, fp, fn, tp = sk["confusion_matrix"](y_test, y_pred, labels=[0, 1]).ravel()

    metrics = {
        "model": name,
        "threshold": threshold,
        "accuracy": float((y_pred == y_test).mean()),
        "precision": float(sk["precision_score"](y_test, y_pred, zero_division=0)),
        "recall": float(sk["recall_score"](y_test, y_pred, zero_division=0)),
        "f1": float(sk["f1_score"](y_test, y_pred, zero_division=0)),
        "pr_auc": float(sk["average_precision_score"](y_test, y_prob)),
        "confusionMatrix": {
            "tn": int(tn),
            "fp": int(fp),
            "fn": int(fn),
            "tp": int(tp),
        },
    }
    try:
        metrics["roc_auc"] = float(sk["roc_auc_score"](y_test, y_prob))
    except Exception:
        metrics["roc_auc"] = None
    return metrics, y_prob


def plot_pr_curves(y_test: np.ndarray, probabilities: Dict[str, np.ndarray],
                   sk: Dict[str, Any], output_path: str) -> bool:
    plt = optional_import("matplotlib.pyplot")
    if plt is None:
        return False
    for name, y_prob in probabilities.items():
        precision, recall, _ = sk["precision_recall_curve"](y_test, y_prob)
        ap = sk["average_precision_score"](y_test, y_prob)
        plt.plot(recall, precision, label="%s AP=%.4f" % (name, ap))
    plt.xlabel("Recall")
    plt.ylabel("Precision")
    plt.title("Precision-Recall Curve Comparison")
    plt.legend()
    plt.grid(True, alpha=0.3)
    plt.tight_layout()
    plt.savefig(output_path, dpi=160)
    plt.close()
    return True


def plot_confusion_matrix(matrix: Dict[str, int], output_path: str) -> bool:
    plt = optional_import("matplotlib.pyplot")
    if plt is None:
        return False
    values = np.array([[matrix["tn"], matrix["fp"]], [matrix["fn"], matrix["tp"]]])
    plt.imshow(values, cmap="Blues")
    plt.xticks([0, 1], ["Pred 0", "Pred 1"])
    plt.yticks([0, 1], ["True 0", "True 1"])
    for row in range(2):
        for col in range(2):
            plt.text(col, row, str(values[row, col]), ha="center", va="center")
    plt.title("Best Model Confusion Matrix")
    plt.colorbar()
    plt.tight_layout()
    plt.savefig(output_path, dpi=160)
    plt.close()
    return True


def extract_feature_importance(model: Any) -> Optional[np.ndarray]:
    if hasattr(model, "feature_importances_"):
        return np.asarray(model.feature_importances_)
    if hasattr(model, "named_steps"):
        classifier = model.named_steps.get("classifier")
        if classifier is not None and hasattr(classifier, "coef_"):
            return np.abs(np.asarray(classifier.coef_[0]))
    return None


def plot_feature_importance(model: Any, feature_columns: List[str], output_path: str) -> bool:
    plt = optional_import("matplotlib.pyplot")
    if plt is None:
        return False
    importance = extract_feature_importance(model)
    if importance is None:
        return False
    order = np.argsort(importance)[-15:]
    plt.barh(np.asarray(feature_columns)[order], importance[order])
    plt.title("Best Model Feature Importance")
    plt.tight_layout()
    plt.savefig(output_path, dpi=160)
    plt.close()
    return True


def maybe_plot_shap(model: Any, x_sample: pd.DataFrame, output_path: str) -> bool:
    shap = optional_import("shap")
    plt = optional_import("matplotlib.pyplot")
    if shap is None or plt is None:
        return False
    try:
        explainer = shap.Explainer(model, x_sample)
        values = explainer(x_sample)
        shap.summary_plot(values, x_sample, show=False)
        plt.tight_layout()
        plt.savefig(output_path, dpi=160)
        plt.close()
        return True
    except Exception:
        plt.close()
        return False


def maybe_export_pmml(model_name: str, model: Any, x_train: pd.DataFrame, y_train: pd.Series,
                      output_path: str) -> Optional[str]:
    if model_name not in {"logistic_regression", "random_forest"}:
        return None
    sklearn2pmml = optional_import("sklearn2pmml", "sklearn2pmml")
    PMMLPipeline = optional_import("sklearn2pmml.pipeline", "PMMLPipeline")
    if sklearn2pmml is None or PMMLPipeline is None:
        return None

    try:
        if hasattr(model, "steps"):
            pmml_pipeline = PMMLPipeline(model.steps)
        else:
            pmml_pipeline = PMMLPipeline([("classifier", model)])
        pmml_pipeline.fit(x_train, y_train)
        sklearn2pmml(pmml_pipeline, output_path, with_repr=True)
        return output_path
    except Exception as exc:
        print("[WARN] PMML export skipped for %s: %s" % (model_name, exc))
        return None


def write_json(path: str, payload: Dict[str, Any]) -> None:
    with open(path, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, indent=2)


def main() -> None:
    parser = argparse.ArgumentParser(description="Train Zephyr-Watch P1 risk classification baselines.")
    parser.add_argument("--input", default=os.path.join(DATA_DIR, "train_dataset.csv"))
    parser.add_argument("--artifact-dir", default=os.path.join(ML_ROOT, "models"))
    parser.add_argument("--report-dir", default=os.path.join(ML_ROOT, "reports"))
    parser.add_argument("--risk-rul-threshold", type=float, default=30.0)
    parser.add_argument("--test-size", type=float, default=0.2)
    parser.add_argument("--random-state", type=int, default=42)
    parser.add_argument("--min-recall", type=float, default=0.75)
    parser.add_argument("--require-all", action="store_true")
    args = parser.parse_args()

    sk = import_required_sklearn()
    os.makedirs(args.artifact_dir, exist_ok=True)
    os.makedirs(args.report_dir, exist_ok=True)

    df = load_dataset(args.input, args.risk_rul_threshold)
    train_df, test_df = split_dataset(df, args.test_size, args.random_state, sk)
    x_train = train_df[FEATURE_COLUMNS]
    y_train = train_df["riskLabel"].astype(int)
    x_test = test_df[FEATURE_COLUMNS]
    y_test = test_df["riskLabel"].astype(int).to_numpy()

    models = build_models(args.random_state, y_train, sk)
    expected_models = {"logistic_regression", "random_forest", "xgboost", "lightgbm"}
    missing_models = sorted(expected_models.difference(models.keys()))
    if args.require_all and missing_models:
        raise RuntimeError("Missing optional model dependencies for: %s" % missing_models)

    metrics: List[Dict[str, Any]] = []
    probabilities: Dict[str, np.ndarray] = {}
    trained_models: Dict[str, Any] = {}
    skipped = [{"model": name, "reason": "optional dependency is not installed"} for name in missing_models]

    for name, model in models.items():
        print("[INFO] Training %s..." % name)
        model.fit(x_train, y_train)
        model_metrics, y_prob = evaluate_model(name, model, x_test, y_test, sk, args.min_recall)
        metrics.append(model_metrics)
        probabilities[name] = y_prob
        trained_models[name] = model
        joblib.dump(model, os.path.join(args.artifact_dir, "%s.pkl" % name))

    metrics = sorted(metrics, key=lambda item: item["pr_auc"], reverse=True)
    best = metrics[0]
    best_model = trained_models[best["model"]]
    best_model_path = os.path.join(args.artifact_dir, "best_risk_model.pkl")
    joblib.dump(best_model, best_model_path)
    optional_pmml_path = maybe_export_pmml(
        best["model"],
        best_model,
        x_train,
        y_train,
        os.path.join(args.artifact_dir, "best_risk_model.pmml"),
    )

    threshold_payload = {
        "riskAlertThreshold": best["threshold"]["threshold"],
        "riskCriticalThreshold": max(0.9, best["threshold"]["threshold"]),
        "rulWarningThreshold": 60.0,
        "rulCriticalThreshold": args.risk_rul_threshold,
        "riskStrategy": "p1_best_pr_auc_classifier",
        "selectedModel": best["model"],
        "minRecall": args.min_recall,
    }
    write_json(os.path.join(args.artifact_dir, "threshold.json"), threshold_payload)
    write_json(os.path.join(args.artifact_dir, "feature_columns.json"), {"featureColumns": FEATURE_COLUMNS})

    report_payload = {
        "createdAt": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "input": args.input,
        "trainSamples": int(len(train_df)),
        "testSamples": int(len(test_df)),
        "positiveTrainSamples": int(y_train.sum()),
        "positiveTestSamples": int(y_test.sum()),
        "metrics": metrics,
        "skippedModels": skipped,
    }
    write_json(os.path.join(args.report_dir, "metrics_report.json"), report_payload)

    model_metadata = {
        "modelVersion": "risk-baseline-%s" % datetime.now().strftime("%Y%m%d%H%M%S"),
        "modelType": "P1_RiskClassification_Baselines",
        "modelRuntime": "REST_PKL",
        "modelUri": "best_risk_model.pkl",
        "optionalPmmlUri": "best_risk_model.pmml" if optional_pmml_path else None,
        "thresholdUri": "threshold.json",
        "featureColumnsUri": "feature_columns.json",
        "metadataUri": "model_metadata.json",
        "bestModel": best["model"],
        "selectionMetric": "pr_auc",
        "metrics": best,
        "status": "LOCAL_READY",
    }
    write_json(os.path.join(args.artifact_dir, "model_metadata.json"), model_metadata)

    plot_pr_curves(y_test, probabilities, sk, os.path.join(args.report_dir, "pr_curve_compare.png"))
    plot_confusion_matrix(best["confusionMatrix"], os.path.join(args.report_dir, "confusion_matrix.png"))
    plot_feature_importance(best_model, FEATURE_COLUMNS, os.path.join(args.report_dir, "feature_importance.png"))
    maybe_plot_shap(best_model, x_test.sample(min(len(x_test), 200), random_state=args.random_state),
                    os.path.join(args.report_dir, "shap_summary.png"))

    print("[INFO] Best model: %s, PR-AUC: %.4f" % (best["model"], best["pr_auc"]))
    if skipped:
        print("[WARN] Skipped optional models: %s" % ", ".join(item["model"] for item in skipped))
    print("[INFO] Artifacts written to %s" % args.artifact_dir)
    print("[INFO] Reports written to %s" % args.report_dir)


if __name__ == "__main__":
    main()
