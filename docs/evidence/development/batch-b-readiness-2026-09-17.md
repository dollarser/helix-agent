# 批次 B 开发前收尾与交接

日期：2026-09-17。基线：本地 main `cd3f744e`（已整合批次 A/P0 与上下文预算优化）。本记录只证明本轮实际检查的范围，不关闭 HXA-193、真实账号、真机长稳或发行验收。

## 收尾范围

- 修正 status/roadmap 的任务统计：按当前索引逐项为20项，3收尾、8待实现、2集成、3待决策、4发行；未立项 proposed ADR 不计入。
- 统一实施指南由已交付批次 A 切换为批次 B，HXA-204/205 增加顺序切片与完整验收边界。适用于 Claude Code 中的小模型，不维护第二份模型专属路线。
- 将执行引擎研究、JSONL 需求 ADR 及入口纳入版本管理；研究对照现有完成记录更新194/203交付边界。ADR-AGENT-005 仍 proposed，未实现、未自动立项。
- 归档之前遗留的无数据调试脚本；移除机器专属路径，将探针输出放 build，修复历史门禁包装脚本最后 echo 掩盖非零退出码的问题。历史探针未重新运行，归档不新增验收结论。
- 生产代码仅同步 ChatScreenProjection 的说明注释，本轮没有改变运行行为。

## 实际验证

| 命令 | 结果与边界 |
| --- | --- |
| `./scripts/check-all.sh --all` | exit 0；源检查、Spotless/Detekt、主机 test、Debug/Release及双flavor lint、应用/Runtime构建、35份依赖锁、制品与订阅边界检查通过。不是签名发布或真机验收 |
| `./gradlew :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest` | exit 0；整合版本双flavor测试APK构建通过 |
| `bash scripts/debug/2026-09-17/run-closeout-context-matrix.sh build/development-closeout/context-matrix` | exit 0；API29/36 × consumer/developer 各30/30，共120项通过、无跳过；四个自有模拟器 closed.json 均 exit 0。不是完整产品设备套件 |
| `./scripts/check-all.sh --source`、`git diff --cached --check`、`./scripts/check-secrets.sh` | 收尾文档/脚本通过源检查、暂存差异检查与 Secret 扫描；历史新增 shell/Python 脚本另完成语法检查 |

设备覆盖六类：ToolResultReadDeviceTest、ChatScreenProjectionDeviceTest、ChatServiceAttachmentRetryDeviceTest、RunControlSettingsUiDeviceTest、LongTurnCompactionDeviceTest、ContextCompactionDeviceTest。验证同会话大结果读取及拒绝边界、工具协议不作为正文、预算继续保留历史及未知结果阻止继续、设置持久化、压缩配对与预算记账。制品哈希与进程归属保留在各象限日志中；不宣称真实模型语义保证或完整恢复旅程验收。

本机原始日志为忽略目录 `build/development-closeout/` 中的 `all.log`、`test-apks.log`、`device.log` 及各象限的 `artifacts.json`、`owner.json`、`instrumentation.txt`、`closed.json`。测试使用离线夹具、自建数据库和独占模拟器，不读取用户业务会话。

## 后续执行边界

下一任务为 [HXA-204](../../completion-records/HXA-204.md)，完整验收后继续 [HXA-205](../../completion-records/HXA-205.md)。采用[统一交接](../../development/implementation-guide.md)，独立 worktree、本地具名提交，不推送、合并或发布。每个新 HXA 仍需核对当前代码与设备基线，已知失败先修；不得把本记录当成未来变更的测试结果。

本轮开始时没有连接真机，因此最新制品真机回归、OEM/Doze/热压力和系统 JNI/Binder 证据仍开放。HXA-193 的干净资产重建、远端 CI、升级恢复以及190/125真实账号范围保持原分类；不阻塞没有这些外部依赖的恢复/UI开发，实际影响构建或初始化的故障仍须先解决。

没有启动 Claude Code 开发进程，也没有替用户选择其模型或登录账号；交接文档与独立工作树是本次安排的交付物。
