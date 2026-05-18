import argparse
import json
import os

import joblib
import numpy as np

try:
    import tensorflow as tf
except ImportError as exc:
    raise ImportError(
        "TensorFlow is required for SavedModel export. Install with: "
        "python -m pip install tensorflow"
    ) from exc


CURRENT_DIR = os.path.dirname(os.path.abspath(__file__))
ML_ROOT = os.path.abspath(os.path.join(CURRENT_DIR, ".."))
DEFAULT_MODEL_PATH = os.path.join(ML_ROOT, "models", "best_risk_model.pkl")
DEFAULT_FEATURE_COLUMNS_PATH = os.path.join(ML_ROOT, "models", "feature_columns.json")
DEFAULT_EXPORT_ROOT = os.path.join(ML_ROOT, "models", "tf_serving")
DEFAULT_MODEL_NAME = "risk_classifier"
DEFAULT_VERSION = 1
DEFAULT_INPUT_NAME = "inputs"


def read_feature_columns(path):
    with open(path, "r", encoding="utf-8") as f:
        payload = json.load(f)
    columns = payload.get("featureColumns") or payload.get("feature_columns")
    if not columns:
        raise ValueError("feature columns not found in " + path)
    return columns


class RiskClassifierModule(tf.Module):
    def __init__(self, mean, scale, coef, intercept, feature_count, input_name):
        super().__init__()
        self.mean = tf.constant(mean, dtype=tf.float32)
        safe_scale = np.where(np.asarray(scale, dtype=np.float32) == 0.0, 1.0, np.asarray(scale, dtype=np.float32))
        self.scale = tf.constant(safe_scale, dtype=tf.float32)
        self.coef = tf.constant(np.asarray(coef, dtype=np.float32).reshape(-1), dtype=tf.float32)
        self.intercept = tf.constant(float(np.asarray(intercept, dtype=np.float32).reshape(-1)[0]), dtype=tf.float32)
        self._serve = self._build_serve_fn(feature_count, input_name)

    def _build_serve_fn(self, feature_count, input_name):
        @tf.function(input_signature=[tf.TensorSpec([None, feature_count], tf.float32, name=input_name)])
        def serve(inputs):
            x = (inputs - self.mean) / self.scale
            logits = tf.linalg.matmul(x, tf.reshape(self.coef, [-1, 1])) + self.intercept
            probability_1 = tf.sigmoid(logits)
            probability_0 = 1.0 - probability_1
            probabilities = tf.concat([probability_0, probability_1], axis=1)
            return {"probabilities": probabilities}

        return serve

    @property
    def serve(self):
        return self._serve


def main():
    parser = argparse.ArgumentParser(
        description="Export the sklearn risk pipeline to a TensorFlow SavedModel for TF Serving."
    )
    parser.add_argument("--model-path", default=DEFAULT_MODEL_PATH, help="Path to best_risk_model.pkl")
    parser.add_argument(
        "--feature-columns-path",
        default=DEFAULT_FEATURE_COLUMNS_PATH,
        help="Path to feature_columns.json",
    )
    parser.add_argument(
        "--export-root",
        default=DEFAULT_EXPORT_ROOT,
        help="Root directory for SavedModel export",
    )
    parser.add_argument("--model-name", default=DEFAULT_MODEL_NAME, help="TF Serving model name")
    parser.add_argument("--version", type=int, default=DEFAULT_VERSION, help="Model version number")
    parser.add_argument("--input-name", default=DEFAULT_INPUT_NAME, help="Serving input name")
    args = parser.parse_args()

    if not os.path.exists(args.model_path):
        raise FileNotFoundError("model file not found: " + args.model_path)
    if not os.path.exists(args.feature_columns_path):
        raise FileNotFoundError("feature columns file not found: " + args.feature_columns_path)

    feature_columns = read_feature_columns(args.feature_columns_path)
    feature_count = len(feature_columns)
    if feature_count <= 0:
        raise ValueError("feature count must be > 0")

    pipeline = joblib.load(args.model_path)
    if not hasattr(pipeline, "named_steps"):
        raise ValueError("expected a sklearn Pipeline with named_steps")

    scaler = pipeline.named_steps.get("scaler")
    classifier = pipeline.named_steps.get("classifier")
    if scaler is None or classifier is None:
        raise ValueError("pipeline must contain scaler and classifier steps")

    for attr in ("mean_", "scale_"):
        if not hasattr(scaler, attr):
            raise ValueError("scaler missing attribute: " + attr)
    for attr in ("coef_", "intercept_"):
        if not hasattr(classifier, attr):
            raise ValueError("classifier missing attribute: " + attr)

    export_dir = os.path.join(args.export_root, args.model_name, str(args.version))
    os.makedirs(export_dir, exist_ok=True)

    module = RiskClassifierModule(
        mean=scaler.mean_,
        scale=scaler.scale_,
        coef=classifier.coef_,
        intercept=classifier.intercept_,
        feature_count=feature_count,
        input_name=args.input_name,
    )

    tf.saved_model.save(module, export_dir, signatures=module.serve)

    print("SavedModel export success")
    print("export_dir=" + export_dir)
    print("model_name=" + args.model_name)
    print("version=" + str(args.version))
    print("input_name=" + args.input_name)
    print("feature_count=" + str(feature_count))
    print("next_step=copy export_dir to node2:/opt/zephyr-tfs/models/" + args.model_name)


if __name__ == "__main__":
    main()
