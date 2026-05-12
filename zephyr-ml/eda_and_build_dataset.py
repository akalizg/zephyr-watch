import pandas as pd
import numpy as np
import matplotlib.pyplot as plt
import seaborn as sns
from sklearn.decomposition import PCA
from sklearn.preprocessing import StandardScaler
from sklearn.ensemble import RandomForestClassifier
from sklearn.model_selection import train_test_split
import joblib
import json
import os
import warnings
warnings.filterwarnings('ignore')

plt.rcParams['font.sans-serif'] = ['SimHei']
plt.rcParams['axes.unicode_minus'] = False


# -------------------- 数据加载 --------------------
def load_data(csv_path='data/train_dataset.csv'):
    if not os.path.exists(csv_path):
        raise FileNotFoundError(f"找不到文件: {csv_path}")
    df = pd.read_csv(csv_path)
    print(f"数据集加载成功！形状: {df.shape}")
    return df


# -------------------- A1: 类别分布 --------------------
def eda_class_distribution(df, label_col='risk_label'):
    counts = df[label_col].value_counts().sort_index()
    print("\n===== 类别分布 =====")
    print(f"正常样本 (0): {counts.get(0, 0)}")
    print(f"高风险样本 (1): {counts.get(1, 0)}")

    plt.figure(figsize=(6, 6))
    labels = ['正常 (0)', '高风险 (1)']
    sizes = [counts.get(0, 0), counts.get(1, 0)]
    colors = ['#66b3ff', '#ff6666']
    explode = (0, 0.05)

    plt.pie(sizes, explode=explode, labels=labels, colors=colors,
            autopct='%1.1f%%', shadow=True, startangle=90)
    plt.title('样本类别分布 (正常 vs 高风险)')
    plt.axis('equal')
    plt.savefig('reports/class_distribution_pie.png', dpi=150, bbox_inches='tight')
    plt.show()


# -------------------- A2: KDE 分布曲线 --------------------
def eda_kde_plots(df, label_col='risk_label', feature_cols=None):
    if feature_cols is None:
        feature_cols = ['pressureAvg', 'pressureStd', 'temperatureAvg',
                        'temperatureStd', 'speedAvg', 'speedTrend']

    # 保留实际存在的列
    feature_cols = [col for col in feature_cols if col in df.columns]
    if not feature_cols:
        print("[警告] 没有可用的特征列用于 KDE 绘图，已跳过。")
        return

    df_norm = df[df[label_col] == 0]
    df_risk = df[df[label_col] == 1]

    n_cols = 3
    n_rows = (len(feature_cols) + n_cols - 1) // n_cols
    fig, axes = plt.subplots(n_rows, n_cols, figsize=(15, n_rows * 4))
    axes = axes.flatten()

    for i, col in enumerate(feature_cols):
        ax = axes[i]
        sns.kdeplot(data=df_norm, x=col, fill=True, alpha=0.4, label='正常', ax=ax)
        sns.kdeplot(data=df_risk, x=col, fill=True, alpha=0.4, label='高风险', ax=ax)
        ax.set_title(f'{col} 分布对比')
        ax.legend()

    for j in range(i + 1, len(axes)):
        axes[j].set_visible(False)

    plt.suptitle('正常/高风险样本在关键特征上的 KDE 分布', fontsize=16)
    plt.tight_layout()
    plt.savefig('reports/kde_feature_distribution.png', dpi=150, bbox_inches='tight')
    plt.show()


# -------------------- A3: PCA 3D 投影 --------------------
def pca_3d_visualization(df, label_col='risk_label', exclude_cols=None):
    import plotly.express as px

    if exclude_cols is None:
        exclude_cols = ['machineId', 'cycle', 'setting1', 'setting2', 'setting3',
                        's1', 's2', 's3', 's4', 's5', 's6', 's7', 's8', 's9', 's10',
                        's11', 's12', 's13', 's14', 's15', 's16', 's17', 's18', 's19', 's20', 's21',
                        'maxCycle', 'RUL', label_col, 'sampleCount', 'cycleStart', 'cycleEnd']

    feature_cols = [col for col in df.columns
                    if col not in exclude_cols and np.issubdtype(df[col].dtype, np.number)]
    print(f"\n用于 PCA 的特征数量: {len(feature_cols)}")

    X = df[feature_cols].dropna()
    y = df.loc[X.index, label_col]

    scaler = StandardScaler()
    X_scaled = scaler.fit_transform(X)

    pca = PCA(n_components=3)
    X_pca = pca.fit_transform(X_scaled)
    explained_var = pca.explained_variance_ratio_
    print(f"PCA 前三个主成分解释方差比例: {explained_var}")

    pca_df = pd.DataFrame(data=X_pca, columns=['PC1', 'PC2', 'PC3'])
    pca_df['risk_label'] = y.values
    pca_df['label_str'] = pca_df['risk_label'].map({0: '正常', 1: '高风险'})

    fig = px.scatter_3d(pca_df, x='PC1', y='PC2', z='PC3',
                         color='label_str',
                         color_discrete_map={'正常': '#66b3ff', '高风险': '#ff6666'},
                         title='PCA 3D 投影：正常 vs 高风险样本',
                         opacity=0.6)
    fig.show()
    fig.write_html("reports/pca_3d_scatter.html")
    print("PCA 3D 图已保存为 reports/pca_3d_scatter.html")


# -------------------- A4: 随机森林特征重要性 --------------------
def random_forest_importance(df, label_col='risk_label', exclude_cols=None):
    if exclude_cols is None:
        exclude_cols = ['machineId', 'cycle', 'setting1', 'setting2', 'setting3',
                        's1', 's2', 's3', 's4', 's5', 's6', 's7', 's8', 's9', 's10',
                        's11', 's12', 's13', 's14', 's15', 's16', 's17', 's18', 's19', 's20', 's21',
                        'maxCycle', 'RUL', label_col, 'sampleCount', 'cycleStart', 'cycleEnd']

    feature_cols = [col for col in df.columns
                    if col not in exclude_cols and np.issubdtype(df[col].dtype, np.number)]
    X = df[feature_cols].fillna(0)
    y = df[label_col]

    X_train, X_test, y_train, y_test = train_test_split(
        X, y, test_size=0.2, stratify=y, random_state=42)

    rf = RandomForestClassifier(n_estimators=50, max_depth=10,
                                random_state=42, n_jobs=-1)
    rf.fit(X_train, y_train)

    importances = rf.feature_importances_
    indices = np.argsort(importances)[::-1]

    print("\n===== 随机森林特征重要性 Top15 =====")
    for i in range(min(15, len(feature_cols))):
        print(f"{i + 1}. {feature_cols[indices[i]]}: {importances[indices[i]]:.5f}")

    plt.figure(figsize=(12, 8))
    plt.title('随机森林特征重要性排名 (Top15)')
    plt.barh(range(min(15, len(feature_cols))),
             [importances[indices[i]] for i in range(min(15, len(feature_cols)))],
             color='skyblue')
    plt.yticks(range(min(15, len(feature_cols))),
               [feature_cols[indices[i]] for i in range(min(15, len(feature_cols)))])
    plt.gca().invert_yaxis()
    plt.xlabel('重要性分数')
    plt.tight_layout()
    plt.savefig('reports/feature_importance_rf.png', dpi=150, bbox_inches='tight')
    plt.show()

    importance_dict = {feature_cols[indices[i]]: importances[indices[i]]
                       for i in range(len(feature_cols))}
    return importance_dict


# -------------------- A5: 构建标准化数据集 --------------------
def build_dataset(df, label_col='risk_label', test_machine_ratio=0.2, random_seed=42):
    # 使用的特征列：仅窗口统计特征（与 Flink 在线推理口径一致）
    feature_cols = ['pressureMin', 'pressureMax', 'pressureAvg', 'pressureStd', 'pressureTrend',
                    'temperatureMin', 'temperatureMax', 'temperatureAvg', 'temperatureStd', 'temperatureTrend',
                    'speedMin', 'speedMax', 'speedAvg', 'speedStd', 'speedTrend']

    # 如果某些列在实际数据中不存在，则自动过滤
    feature_cols = [col for col in feature_cols if col in df.columns]
    if not feature_cols:
        raise ValueError("没有找到任何窗口特征列，请检查数据集列名。")

    print(f"\n构建数据集使用的特征列 ({len(feature_cols)} 个): {feature_cols}")

    # 按 machineId 分组划分
    id_col = 'machineId'  # 你的数据集中是 machineId，不是 machine_id
    unique_machines = df[id_col].unique()
    np.random.seed(random_seed)
    np.random.shuffle(unique_machines)
    split_idx = int(len(unique_machines) * (1 - test_machine_ratio))
    train_machines = unique_machines[:split_idx]
    test_machines = unique_machines[split_idx:]

    train_df_raw = df[df[id_col].isin(train_machines)].copy()
    test_df_raw = df[df[id_col].isin(test_machines)].copy()

    print(f"训练集机器数: {len(train_machines)}, 样本数: {len(train_df_raw)}")
    print(f"测试集机器数: {len(test_machines)}, 样本数: {len(test_df_raw)}")

    X_train_raw = train_df_raw[feature_cols].values
    y_train = train_df_raw[label_col].values
    X_test_raw = test_df_raw[feature_cols].values
    y_test = test_df_raw[label_col].values

    # 标准化
    scaler = StandardScaler()
    X_train_scaled = scaler.fit_transform(X_train_raw)
    X_test_scaled = scaler.transform(X_test_raw)

    os.makedirs('data/processed', exist_ok=True)

    # 保存为 npz
    np.savez_compressed('data/processed/train.npz', X=X_train_scaled, y=y_train)
    np.savez_compressed('data/processed/test.npz', X=X_test_scaled, y=y_test)

    # 同时保存 CSV（保留元数据，特征列替换为标准化的值）
    train_out = train_df_raw.copy()
    train_out[feature_cols] = X_train_scaled
    train_out.to_csv('data/processed/train_dataset_processed.csv', index=False)

    test_out = test_df_raw.copy()
    test_out[feature_cols] = X_test_scaled
    test_out.to_csv('data/processed/test_dataset_processed.csv', index=False)

    # 保存 scaler 和特征列列表
    joblib.dump(scaler, 'models/scaler.pkl')
    with open('models/feature_columns.json', 'w') as f:
        json.dump(feature_cols, f, indent=2)

    print("标准化器已保存到 models/scaler.pkl")
    print("特征列列表已保存到 models/feature_columns.json")
    print("处理后的训练/测试数据已保存到 data/processed/")


# -------------------- 主程序 --------------------
if __name__ == '__main__':
    os.makedirs('reports', exist_ok=True)
    os.makedirs('models', exist_ok=True)
    os.makedirs('data/processed', exist_ok=True)

    df = load_data('data/train_dataset.csv')

    # 根据 RUL 生成风险标签（RUL <= 30 为高风险）
    df['risk_label'] = (df['RUL'] <= 30).astype(int)

    label_col = 'risk_label'

    # 依次执行分析
    eda_class_distribution(df, label_col=label_col)

    eda_kde_plots(df, label_col=label_col)

    pca_3d_visualization(df, label_col=label_col)

    random_forest_importance(df, label_col=label_col)

    build_dataset(df, label_col=label_col)

    print("\n====== 所有分析任务完成！ ======")
    print("1. reports/class_distribution_pie.png")
    print("2. reports/kde_feature_distribution.png")
    print("3. reports/pca_3d_scatter.html")
    print("4. reports/feature_importance_rf.png")
    print("5. models/scaler.pkl, models/feature_columns.json")
    print("6. data/processed/train.npz / test.npz (以及 CSV 版本)")