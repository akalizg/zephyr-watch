# create_valid_csv.py
import pandas as pd
import os

# 文件路径
file_path = r"D:\软件\机器学习\zephyr-watch-main (1)\zephyr-watch-main\zephyr-ml-2\data\feedback_training_sample.csv"

# 确保目录存在
os.makedirs(os.path.dirname(file_path), exist_ok=True)

# 创建完整的数据（包含训练脚本需要的所有列）
df = pd.DataFrame({
    'machine_id': [1, 2, 1],
    'window_start': [1000, 2000, 3000],
    'window_end': [2000, 3000, 4000],
    'sample_count': [100, 150, 120],
    'cycle_start': [0, 100, 50],
    'cycle_end': [100, 200, 150],
    'pressure_min': [10.5, 11.2, 10.8],
    'pressure_max': [20.5, 21.2, 20.8],
    'pressure_avg': [15.2, 16.1, 15.8],
    'pressure_std': [2.1, 2.3, 2.0],
    'pressure_trend': [0.1, -0.05, 0.08],
    'temperature_min': [30.5, 31.2, 30.8],
    'temperature_max': [40.5, 41.2, 40.8],
    'temperature_avg': [35.2, 36.1, 35.8],
    'temperature_std': [3.1, 3.3, 3.0],
    'temperature_trend': [0.2, -0.1, 0.15],
    'speed_min': [500, 520, 510],
    'speed_max': [600, 620, 610],
    'speed_avg': [550, 570, 560],
    'speed_std': [10.5, 11.2, 10.8],
    'speed_trend': [0.05, -0.02, 0.03],
    'rul': [500, 450, 480],
    'risk_label': [1, 0, 1],
    'review_label': [2, 1, 2],
    'reviewer': ['admin', 'operator', 'admin'],
    'alert_id': ['ALERT-001', 'ALERT-002', 'ALERT-003'],
    'created_at': [1778507167, 1778507168, 1778507169]
})

# 保存为 CSV
df.to_csv(file_path, index=False, encoding='utf-8')

print(f"✓ 文件已创建: {file_path}")
print(f"✓ 数据行数: {len(df)}")
print(f"✓ 列数: {len(df.columns)}")
print(f"✓ 文件大小: {os.path.getsize(file_path)} 字节")
print("\n前3行数据:")
print(df.head(3))