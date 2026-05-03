import argparse
import json
import os
from typing import Dict

import requests
from minio import Minio


CURRENT_DIR = os.path.dirname(os.path.abspath(__file__))
ML_ROOT = os.path.abspath(os.path.join(CURRENT_DIR, ".."))
MODEL_DIR = os.path.join(ML_ROOT, "models")

DEFAULT_API_BASE = os.environ.get("ZEPHYR_API_BASE", "http://localhost:8080")
MINIO_ENDPOINT = os.environ.get("ZEPHYR_MINIO_ENDPOINT", "localhost:9000")
MINIO_ACCESS_KEY = os.environ.get("ZEPHYR_MINIO_ACCESS_KEY", "minioadmin")
MINIO_SECRET_KEY = os.environ.get("ZEPHYR_MINIO_SECRET_KEY", "minioadmin")
MINIO_BUCKET = os.environ.get("ZEPHYR_MINIO_BUCKET", "zephyr-models")
MINIO_SECURE = os.environ.get("ZEPHYR_MINIO_SECURE", "false").lower() == "true"


def load_json(path: str) -> Dict:
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def minio_client() -> Minio:
    return Minio(
        MINIO_ENDPOINT,
        access_key=MINIO_ACCESS_KEY,
        secret_key=MINIO_SECRET_KEY,
        secure=MINIO_SECURE,
    )


def upload_to_minio(client: Minio, local_path: str, object_name: str) -> str:
    if not os.path.exists(local_path):
        raise FileNotFoundError(local_path)
    if not client.bucket_exists(MINIO_BUCKET):
        client.make_bucket(MINIO_BUCKET)
    client.fput_object(MINIO_BUCKET, object_name, local_path)
    return "minio://%s/%s" % (MINIO_BUCKET, object_name)


def build_registry_payload() -> Dict:
    metadata_path = os.path.join(MODEL_DIR, "model_metadata.json")
    metadata = load_json(metadata_path)
    model_version = metadata["modelVersion"]
    base_object_path = "risk/%s" % model_version
    client = minio_client()

    model_uri = upload_to_minio(
        client,
        os.path.join(MODEL_DIR, "best_risk_model.pkl"),
        base_object_path + "/best_risk_model.pkl",
    )
    threshold_uri = upload_to_minio(
        client,
        os.path.join(MODEL_DIR, "threshold.json"),
        base_object_path + "/threshold.json",
    )
    feature_columns_uri = upload_to_minio(
        client,
        os.path.join(MODEL_DIR, "feature_columns.json"),
        base_object_path + "/feature_columns.json",
    )
    metadata_uri = upload_to_minio(
        client,
        metadata_path,
        base_object_path + "/model_metadata.json",
    )
    pmml_path = os.path.join(MODEL_DIR, "best_risk_model.pmml")
    optional_pmml_uri = None
    if os.path.exists(pmml_path):
        optional_pmml_uri = upload_to_minio(
            client,
            pmml_path,
            base_object_path + "/best_risk_model.pmml",
        )

    return {
        "modelVersion": model_version,
        "modelType": metadata.get("modelType", "P1_RiskClassification_Baselines"),
        "modelUri": model_uri,
        "thresholdUri": threshold_uri,
        "featureColumnsUri": feature_columns_uri,
        "metadataUri": metadata_uri,
        "optionalPmmlUri": optional_pmml_uri,
        "status": "READY",
    }


def register_model(api_base: str, payload: Dict) -> Dict:
    response = requests.post(api_base.rstrip("/") + "/api/models", json=payload, timeout=10)
    response.raise_for_status()
    return response.json()


def activate_model(api_base: str, model_version: str) -> Dict:
    response = requests.post(
        api_base.rstrip("/") + "/api/models/%s/activate" % model_version,
        timeout=10,
    )
    response.raise_for_status()
    return response.json()


def main():
    parser = argparse.ArgumentParser(description="Upload risk model artifacts to MinIO and register them.")
    parser.add_argument("--api-base", default=DEFAULT_API_BASE)
    parser.add_argument("--activate", action="store_true")
    args = parser.parse_args()

    payload = build_registry_payload()
    print(json.dumps({"registerPayload": payload}, ensure_ascii=False, indent=2))

    register_result = register_model(args.api_base, payload)
    print(json.dumps({"registerResult": register_result}, ensure_ascii=False, indent=2))

    if args.activate:
        activate_result = activate_model(args.api_base, payload["modelVersion"])
        print(json.dumps({"activateResult": activate_result}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
