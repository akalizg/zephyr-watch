from typing import Dict, Any

import numpy as np
from sklearn.metrics import (
    accuracy_score,
    average_precision_score,
    confusion_matrix,
    precision_score,
    recall_score,
    f1_score,
    mean_absolute_error,
    mean_squared_error,
    roc_auc_score,
)


def evaluate_classification(y_true, y_pred, y_prob=None) -> Dict[str, Any]:
    result = {
        "accuracy": float(accuracy_score(y_true, y_pred)),
        "precision": float(precision_score(y_true, y_pred, zero_division=0)),
        "recall": float(recall_score(y_true, y_pred, zero_division=0)),
        "f1": float(f1_score(y_true, y_pred, zero_division=0)),
    }

    if y_prob is not None:
        try:
            result["roc_auc"] = float(roc_auc_score(y_true, y_prob))
        except Exception:
            result["roc_auc"] = None
        try:
            result["pr_auc"] = float(average_precision_score(y_true, y_prob))
        except Exception:
            result["pr_auc"] = None

    tn, fp, fn, tp = confusion_matrix(y_true, y_pred, labels=[0, 1]).ravel()
    result["confusion_matrix"] = {
        "tn": int(tn),
        "fp": int(fp),
        "fn": int(fn),
        "tp": int(tp),
    }

    return result


def find_best_threshold(y_true, y_prob, min_recall: float = 0.75) -> Dict[str, Any]:
    best = {
        "threshold": 0.5,
        "f1": -1.0,
        "precision": 0.0,
        "recall": 0.0,
    }

    for i in range(1, 100):
        threshold = i / 100.0
        y_pred = (y_prob >= threshold).astype(int)
        precision = float(precision_score(y_true, y_pred, zero_division=0))
        recall = float(recall_score(y_true, y_pred, zero_division=0))
        f1 = float(f1_score(y_true, y_pred, zero_division=0))
        if recall >= min_recall and f1 > best["f1"]:
            best = {
                "threshold": threshold,
                "f1": f1,
                "precision": precision,
                "recall": recall,
            }

    if best["f1"] < 0:
        return {
            "threshold": 0.5,
            "f1": float(f1_score(y_true, y_prob >= 0.5, zero_division=0)),
            "precision": float(precision_score(y_true, y_prob >= 0.5, zero_division=0)),
            "recall": float(recall_score(y_true, y_prob >= 0.5, zero_division=0)),
        }
    return best


def evaluate_regression(y_true, y_pred) -> Dict[str, Any]:
    rmse = float(np.sqrt(mean_squared_error(y_true, y_pred)))
    mae = float(mean_absolute_error(y_true, y_pred))
    return {
        "rmse": rmse,
        "mae": mae,
    }
