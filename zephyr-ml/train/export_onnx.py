import argparse
import json
import os

import joblib
import numpy as np
try:
    from skl2onnx import convert_sklearn
    from skl2onnx.common.data_types import FloatTensorType
except ImportError as exc:
    raise ImportError(
        "Missing ONNX export dependencies. Install with: "
        "python -m pip install skl2onnx onnx"
    ) from exc


CURRENT_DIR = os.path.dirname(os.path.abspath(__file__))
ML_ROOT = os.path.abspath(os.path.join(CURRENT_DIR, ".."))
DEFAULT_MODEL_PATH = os.path.join(ML_ROOT, "models", "best_risk_model.pkl")
DEFAULT_FEATURE_COLUMNS_PATH = os.path.join(ML_ROOT, "models", "feature_columns.json")
DEFAULT_OUTPUT_PATH = os.path.join(ML_ROOT, "models", "best_risk_model.onnx")


def read_feature_columns(path):
    with open(path, "r", encoding="utf-8") as f:
        payload = json.load(f)
    columns = payload.get("featureColumns") or payload.get("feature_columns")
    if not columns:
        raise ValueError("feature columns not found in " + path)
    return columns


def build_sample_input(feature_count):
    # Dummy shape check only, values are irrelevant for conversion.
    return np.zeros((1, feature_count), dtype=np.float32)


def main():
    parser = argparse.ArgumentParser(description="Export best_risk_model.pkl to ONNX for Flink ONNX backend.")
    parser.add_argument("--model-path", default=DEFAULT_MODEL_PATH, help="Path to trained .pkl model")
    parser.add_argument("--feature-columns-path", default=DEFAULT_FEATURE_COLUMNS_PATH, help="Path to feature columns json")
    parser.add_argument("--output-path", default=DEFAULT_OUTPUT_PATH, help="Output ONNX file path")
    parser.add_argument("--opset", type=int, default=13, help="ONNX opset version")
    parser.add_argument("--input-name", default="input", help="ONNX input tensor name")
    parser.add_argument("--output-name", default="probabilities", help="ONNX output name hint")
    args = parser.parse_args()

    if not os.path.exists(args.model_path):
        raise FileNotFoundError("model file not found: " + args.model_path)
    if not os.path.exists(args.feature_columns_path):
        raise FileNotFoundError("feature columns file not found: " + args.feature_columns_path)

    feature_columns = read_feature_columns(args.feature_columns_path)
    feature_count = len(feature_columns)
    if feature_count <= 0:
        raise ValueError("feature count must be > 0")

    model = joblib.load(args.model_path)
    _ = build_sample_input(feature_count)

    initial_types = [(args.input_name, FloatTensorType([None, feature_count]))]
    onnx_model = convert_sklearn(
        model,
        initial_types=initial_types,
        target_opset=args.opset,
    )

    os.makedirs(os.path.dirname(args.output_path), exist_ok=True)
    with open(args.output_path, "wb") as f:
        f.write(onnx_model.SerializeToString())

    print("ONNX export success")
    print("model_path=" + args.model_path)
    print("output_path=" + args.output_path)
    print("feature_count=" + str(feature_count))
    print("suggest_input_name=" + args.input_name)
    print("suggest_output_name=" + args.output_name)
    print("suggest_env=" + "ZEPHYR_ONNX_MODEL_PATH=" + args.output_path)


if __name__ == "__main__":
    main()
