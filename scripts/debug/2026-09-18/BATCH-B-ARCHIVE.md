# Batch B 历史设备矩阵脚本

2026-09-20 从已合入 main 的 Batch B 工作树保留两个未提交的原始脚本，内容不作功能修改：

- `hxa204/run-hxa204-matrix.sh`：RecoveryJourney 与 Tasks 的四象限旅程。
- `hxa205/run-hxa205-matrix.sh`：CapabilityReadiness 与 developer Runtime 的四象限旅程。

来源基线为 `f3b5c621`，用于理解 HXA-204/205 原始验收方式，不是新的执行证据或默认 CI 入口。正式整合阶段已另有 `run-merged-runtime-readiness.py`；以当前任务及验收矩阵选择运行入口。

原脚本固定 AVD、端口与输出目录；205 会删除其旧输出目录。复用前须核对本机环境、保存既有证据、显式构建匹配的主/测试 APK，并通过共享 host slot 执行；不要仅因归档到 main 就直接启动。此次仅检查 shell 语法和源门禁，没有重跑历史设备矩阵。
