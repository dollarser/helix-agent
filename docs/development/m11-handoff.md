# M11 合并交接

日期：2026-09-06。

## 已合入

`codex/m11-cli-subscription-remaining` 已 fast-forward 合入本地 `main`，HEAD 为 `4572911`，未推送。HXA-110～119、131～146 的实现/负向 Spike 结论及完成记录均在 Git 中；编号冲突处理见 [编号迁移](m11-main-numbering.md)。

四个平台的文本 Provider 已进入 developer/Advanced：Codex 有真实对话证据；Copilot 当前账号通过 `claude-haiku-4.5` 普通 probe/Chat，见 [HXA-146](../completion-records/HXA-146.md)；Claude/Grok 直接订阅付费调用按所有者决定保持未核实。官方 CLI/SDK Android 路线已停止，不要重新引入。凭据仍由独立 Runtime APK/UID 持有；consumer/store、工具/图片及更多模型协议不因本次合并自动开放。

## main 原有修改

合并前 main 有大量并行未提交修改，未打包进本次提交。只临时 stash 11 个重叠文件，合并后恢复；字符串冲突保留并行工作的 PRoot 文案迁移至 developer 与 M11 订阅文案，状态文档保留 M10 验证缺口和 M11 新进展。`ProcessDiagnostics.kt` 的本地修复已与 M11 提交逐字一致，因此不再作为未提交差异出现，不是被丢弃。

恢复后的并行改动保持未暂存；不要直接 `git add .`。保护性 stash `bc9b7741d3d69a0ab466a5fbb85f349a70aafb92` 暂时保留，**已经恢复，不要重复 apply**。

## 验证与现场

合并后 `check-docs.sh`、`verify-adr.sh`、`check-i18n.sh` 与 `git diff --check` 通过，无未解决 Git 冲突。M11 完成记录的设备/R8/真实请求证据是在 M11 工作树完成，不能当成叠加 main 全部未提交改动后的全量验收。

合并后执行 `./gradlew :app:compileConsumerDebugKotlin :app:compileDeveloperDebugKotlin --no-configuration-cache --no-daemon --max-workers=2`，exit 0（147 tasks，14 s）；证明当前双变体 Kotlin 接线可编译，不代替全量测试或设备验收。

专属模拟器 `Helix_M11_Test_API_29`（5602）和 `Helix_M11_Test_API_36`（5604）供后续显式接管；不要操作其他任务 AVD。可见 API 36 使用 `hw.keyboard=yes` 与 `-no-snapshot`，不恢复旧快照。账号凭据只留在模拟器 Runtime 内，未导出、复制或提交。

源工作树没有待复制的未跟踪交接文件；构建缓存、APK 和原始日志未批量复制。可复现命令、artifact hash、已知限制在完成记录中。本交接文件留在 main 工作树，供并行收尾任务自行审阅暂存。
