# 开发证据库

本目录保存开发过程中的验收、整合、调查、复核和历史进度快照。文件数量较多，**不要按目录列表推断当前状态**；当前任务只看 `docs/development/status.md` 和 active HXA。

建议按目的检索：

- **验收/整合**：`acceptance-*`、`branch-*`、`main-*integration*`、`main-*verification*`。
- **HXA / milestone 过程证据**：`hxa*`、`m7-*`、`m9-*`、`m10-*`。完成结果优先看 `docs/completion-records/HXA-NNN.md`。
- **审查/复核**：`*-review-*`、`*-audit*`、`improvement-*`。这些通常是某个时间点的分析，不是 current backlog。
- **Runtime / recovery / native 调查**：`native-*`、`proot-*`、`cli-*`、`webview-*`、`*-recovery-*`。
- **历史验证计划**：[verification-plans/](verification-plans/README.md)：设备、长稳及公共 Benchmark 的原范围，不是当前执行授权。

近期常用：

- [2026-10-02 HXA-242 HTTP / UI 主机验收](hxa242-http-ui-host-2026-10-02.md)：明文 HTTP 仅提示、管理滚条、Composer 和 Mobile Use 修补，根单测/静态分析/四 APK 及实际网络配置通过；设备未请求。

- [2026-10-02 HXA-241 模拟器报告复核](hxa241-emulator-sweep-reconciliation-2026-10-02.md)：历史失败逐类判断、产品修复、测试契约与驱动收敛；API36 / ARM64 两渠道普通矩阵及适用恢复场景专项通过，记录最终源码/APK 身份和未覆盖边界。

- [2026-10-02 HXA-240 FFmpeg 复用 Bash/Job](hxa240-ffmpeg-proot-2026-10-02.md)：Advanced 的 Bionic CLI 桥、流式输入、多文件会话产物及当前主机/制品/设备边界；替代未验收的独立媒体运行时草稿。

- [2026-10-02 HXA-239 会话输入](hxa239-composer-2026-10-02.md)：未选模型的发送前提示、指令补全光标、底部权限快捷切换，以及主机和设备验收边界。
- [2026-10-02 HXA-238 执行与 Provider 表单](hxa238-execution-provider-2026-10-02.md)：取消终端/后台全局执行锁、实际引擎与物理容量边界、表单滚动/缺项定位，以及当前验证与设备待验范围。
- [2026-10-01 FFmpeg Ready 完成与交接](ffmpeg-ready-preintegration-2026-10-01.md)：两套独立裁剪候选的历史完成/修补清单、产物校验和复建入口；当前接入以 HXA-240 为准，旧实验不作为当前设备授权或通过证明。
- [2026-09-29 最终本地收口](final-closeout-2026-09-29.md)：冻结源码 P5、实际工具视觉及手机剩余验证边界。
- [2026-09-29 分支收敛](branch-convergence-2026-09-29.md)：代码和文档合并的原始范围。
- [2026-09-29 文档整理](documentation-convergence-2026-09-29.md)：改名/归档/汇总清单和机械校验。

- [branch-integration-2026-09-22.md](branch-integration-2026-09-22.md)
- [acceptance-199-206-2026-09-21.md](acceptance-199-206-2026-09-21.md)
- [completed-handoffs-2026-09-22.md](completed-handoffs-2026-09-22.md)
- [document-review-convergence-2026-09-22.md](document-review-convergence-2026-09-22.md)
- [repository-hygiene-2026-09-22.md](repository-hygiene-2026-09-22.md)
- [documentation-review-history-2026-09-02.md](documentation-review-history-2026-09-02.md)

新 evidence 应带日期/基线、实际执行事实和未覆盖边界。一次性工作指令不要放这里；需要长期决策进入 ADR，需要当前实施进入 HXA，需要最终任务交付进入 completion record。
