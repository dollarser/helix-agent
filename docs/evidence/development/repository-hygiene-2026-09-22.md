# 脚本入口与实验构建整理

2026-09-22；基于 `8bf2a829`。本轮整理工程入口，不改产品代码或实验结论。

## 修改

- 四个常用工具迁至 `scripts/run-owned-emulator.py`、`scripts/run-owned-acceptance-emulator.py`、`scripts/with-host-slot.py`、`scripts/run-acceptance-matrix.py`。维护中的任务、验收入口和调用者使用新路径；旧日期路径保留可执行/可导入的兼容入口，历史证据无需批量改写。
- 验收矩阵仍有按任务组织的fixture辅助脚本，不宣称整个debug目录已去依赖。140份不可执行历史归档继续保留；不按日期批量删除或忽略。
- 普通Gradle构建不加载三个Spike；`-PincludeSpikes=true`可复现实验。共享全量门禁、锁文件检查、两个A2A专项脚本显式启用，保留测试和锁文件覆盖。历史直接调用Spike的命令需增加该参数。
- 保留`:testing`及其四项评测数据校验，不为减少模块数量迁移测试。保留现有`.DS_Store`规则；增加日志与个人编辑器local配置忽略，不屏蔽团队共享配置。
- 一次性迁移脚本运行前保存于debug日期目录，执行后移入ignored `build/repository-hygiene/`，其结果由本轮diff记录，不新增永久迁移脚本。

## 验证

使用JDK17和Android SDK，在共享host slot运行：

- `./gradlew projects spotlessCheck --no-daemon`通过，默认项目树无Spike。
- `./gradlew -PincludeSpikes=true projects :spikes:a2a-sdk:testDebugUnitTest :spikes:a2a-minimal:testDebugUnitTest :spikes:bounded-orchestration:testDebugUnitTest :testing:test --no-daemon`通过，显式项目树包含Spike；随后用`--rerun-tasks`重跑上述四个测试任务，避免将旧缓存报告作为新测试。
- 三个稳定runner的`--help`通过；旧路径的`test-owned-emulator.py`两项检查通过。发现旧测试构造参数缺少`reboot_after_setup`，补齐测试输入，未放宽拒绝已有设备的行为。
- `./scripts/check-all.sh --source`、`git diff --check`通过。

原始日志位于ignored `build/repository-hygiene/`。未启动设备，未跑全产品构建/验收，未测量耗时改善，也未将约42MB正常构建输出清理当作产品优化。不改写Git历史，不删除已有诊断证据，不推送远端。
