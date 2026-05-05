from pathlib import Path
import subprocess
import sys


CURRENT_DIR = Path(__file__).resolve().parent
ML_ROOT = CURRENT_DIR.parent
MODEL_PATH = ML_ROOT / "models" / "best_risk_model.pkl"
TRAIN_DATA_PATH = ML_ROOT / "data" / "train_dataset.csv"
TRAIN_SCRIPT_PATH = ML_ROOT / "train" / "train_baselines.py"


def main() -> int:
    if MODEL_PATH.exists():
        print("OK，模型文件已存在: %s" % MODEL_PATH)
        return 0

    if not TRAIN_DATA_PATH.exists():
        print("缺少 best_risk_model.pkl 且缺少 data/train_dataset.csv，无法自动生成模型")
        return 1

    if not TRAIN_SCRIPT_PATH.exists():
        print("缺少 best_risk_model.pkl 且缺少 train/train_baselines.py，无法自动生成模型")
        return 1

    command = [
        sys.executable,
        str(TRAIN_SCRIPT_PATH.relative_to(ML_ROOT)),
        "--input",
        str(TRAIN_DATA_PATH.relative_to(ML_ROOT)),
    ]
    print("未找到模型文件，开始自动训练生成: %s" % MODEL_PATH)
    print("执行命令: %s" % " ".join(command))
    result = subprocess.run(command, cwd=str(ML_ROOT))
    if result.returncode != 0:
        print("模型自动生成失败，返回码: %s" % result.returncode)
        return result.returncode

    if MODEL_PATH.exists():
        print("OK，模型文件已生成: %s" % MODEL_PATH)
        return 0

    print("训练命令执行完成，但仍未找到模型文件: %s" % MODEL_PATH)
    return 1


if __name__ == "__main__":
    sys.exit(main())
