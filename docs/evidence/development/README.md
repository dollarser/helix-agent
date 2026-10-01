# 开发证据库

本目录保存开发过程中的验收、整合、调查、复核和历史进度快照。文件数量较多，**不要按目录列表推断当前状态**；当前任务只看 `docs/development/status.md` 和 active HXA。

建议按目的检索：

- **验收/整合**：`acceptance-*`、`branch-*`、`main-*integration*`、`main-*verification*`。
- **HXA / milestone 过程证据**：`hxa*`、`m7-*`、`m9-*`、`m10-*`。完成结果优先看 `docs/completion-records/HXA-NNN.md`。
- **审查/复核**：`*-review-*`、`*-audit*`、`improvement-*`。这些通常是某个时间点的分析，不是 current backlog。
- **Runtime / recovery / native 调查**：`native-*`、`proot-*`、`cli-*`、`webview-*`、`*-recovery-*`。
- **历史验证计划**：[verification-plans/](verification-plans/README.md)：设备、长稳及公共 Benchmark 的原范围，不是当前执行授权。

近期常用：

- [2026-10-02 HXA-238 执行与 Provider 表单](hxa238-execution-provider-2026-10-02.md)：取消终端/后台全局执行锁、实际引擎与物理容量边界、表单滚动/缺项定位，以及当前验证与设备待验范围。
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
