# Best Model Summary

- 最优模型名称：`logistic_regression`
- 选择依据：按 PR-AUC 最高选择，强调高风险少数类识别能力。
- PR-AUC：`0.962806`
- Precision：`0.891374`
- Recall：`0.900000`
- F1：`0.895666`
- 最优阈值：`0.8300`

## 为什么不主要看 Accuracy

高风险样本通常是少数类，Accuracy 容易被大量正常样本稀释。当前报告更关注 Precision、Recall、F1 和 PR-AUC，用来衡量告警是否能及时发现高风险且控制误报。

## 当前模型局限

- 当前模型仍是表格特征上的传统机器学习基线，没有宣称完成 ONNX、TF Serving 或深度推荐/深度时序模型。
- 阈值来自离线评估，线上仍需结合人工审核反馈持续校准。
- 真实融合指标需要在可用训练环境中运行 `train_baselines.py`，该脚本已支持基于预测概率的 PR-AUC 加权融合。
