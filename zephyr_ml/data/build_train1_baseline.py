import json
import os
from pathlib import Path

import pandas as pd

SOURCE_FILE = Path("D:/Javatest/zephyr-watch/data/train_FD001.txt")
OUTPUT_DIR = Path("artifacts")
OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

def main():
    if not SOURCE_FILE.exists():
        raise FileNotFoundError(f"找不到源文件: {SOURCE_FILE}")

    rows = []
    with SOURCE_FILE.open("r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            parts = line.split()
            rows.append({
                "machine_id": int(parts[0]),
                "cycle": int(parts[1]),
                "pressure": float(parts[6]),
                "temperature": float(parts[7]),
                "speed": float(parts[8]),
            })

    df = pd.DataFrame(rows)

    per_machine = (
        df.groupby("machine_id")
          .agg(
              row_cnt=("cycle", "count"),
              max_cycle=("cycle", "max")
          )
          .reset_index()
          .sort_values("machine_id")
    )

    baseline = {
        "source_file": str(SOURCE_FILE),
        "expected_total_rows": int(len(df)),
        "expected_machine_count": int(df["machine_id"].nunique()),
        "min_machine_id": int(df["machine_id"].min()),
        "max_machine_id": int(df["machine_id"].max()),
    }

    with open(OUTPUT_DIR / "train1_baseline.json", "w", encoding="utf-8") as f:
        json.dump(baseline, f, ensure_ascii=False, indent=2)

    per_machine.to_csv(OUTPUT_DIR / "train1_per_machine.csv", index=False, encoding="utf-8")

    print("=== train1 基线已生成 ===")
    print(json.dumps(baseline, ensure_ascii=False, indent=2))
    print(f"每台设备统计文件: {OUTPUT_DIR / 'train1_per_machine.csv'}")

if __name__ == "__main__":
    main()