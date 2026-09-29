# 分支与工作树收敛

日期：2026-09-29。所有者授权本地整合与清理，并明确所有发布分支保留；本轮不推送、不执行设备或真实服务验证。

## 整合范围

- `7f3249b8` 保存主目录已有的 HXA-225 视觉反馈、会话输入恢复、相关测试与文档；这些工作此前留在 main 工作目录。
- `3df0097e` 保存 Provider 三类入口、可选 API Key、模型发现及设置逐级返回与恢复交互；`1cb97fb1` 将其合并到 main。ProviderFactory 与三语言资源自动合并，后续以联合主机门禁验证。
- 既有契约收敛 `12b1c641` 已在 main，本轮没有实施 HXA-231 R1。

## 清理与保留

- 删除已包含在 main 的本地 `codex/provider-settings`、`codex/contract-convergence`、`refactor/clean-slate-engine` 分支。
- 使用应用归档功能回收 Provider 与 contract-convergence 两个工作树；后者的未提交内容主要为原主目录快照，归档保留其完整恢复记录。
- 保留 `v0.0.1`（`2251242c`）、`v0.0.2`（`43e8aef6`）、`codex/release-v0.0.3`（`3d417683`）。前述发布分支在所有者补充指令前曾删除本地引用，随即按原提交恢复；没有删除提交、发布标签或远端分支。额外保留本地 `archive/v0.0.1-pre-convergence-20260929` 标签。
- 保留 BioHelix 独立材料分支及工作树，其 PDF 不属于产品代码整合。
- 保留 public-benchmark-eval 及其工作树。其未提交 runner 含机器路径，AndroidWorld 夹具删除了显式启用检查，尚不适合直接并入主线；本次未改动或运行这些脚本。已保存快照，不将待审查脚本误报为完成的全量评测能力。
- 整合前四份逐文件哈希、原始补丁和文件副本在忽略目录 `build/branch-convergence-20260929/`；归档前另保存两个工作树的根 build 内容至该目录的 `retained/`，避免归档遗漏既有日志。

## 当前验证

联合 `check-all.sh --all` 通过共享主机槽执行并以退出码 0 完成：全部 JVM tests、静态检查、双渠道 Debug/Release APK、38 份依赖锁及制品边界检查通过；日志为 `build/branch-convergence-20260929/host-all.log`。双渠道 AndroidTest APK 另行编译通过（466 个构建 task，不是设备测试数），日志为同目录 `androidtest-build.log`。最终源码门禁通过（629 Markdown、214 HXA、35 ADR、1811 三语言资源键），日志 `final-source.log`；`git diff --check` 通过。

设备与真实服务均为 `not requested`。此前各切片的设备证据只证明当时制品，不能替代此次合并后的设备回归。未推送、未执行远端 CI、未发布 APK。
