import argparse


def main():
    parser = argparse.ArgumentParser(description="Train Zephyr-Watch XGBoost risk model.")
    parser.add_argument("--input", required=True)
    parser.add_argument("--artifact-dir", required=True)
    args = parser.parse_args()
    raise NotImplementedError(
        "XGBoost risk classification training is reserved for the P1 model upgrade."
    )


if __name__ == "__main__":
    main()
