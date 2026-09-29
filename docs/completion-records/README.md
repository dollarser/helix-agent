# HXA 完成记录

M1 起，每个完成的 HXA 使用一个独立文件：`HXA-NNN.md`。内容与交接遵循[实施指南](../development/implementation-guide.md#交接输出)，写入实际命令、exit code、设备、产物、限制和 ADR 状态；旧路线文档中的模板章节已不再维护。

所有已登记任务见 [完成记录索引](index.md)。新增或修改记录标题后运行 `python3 scripts/generate-completion-index.py`；索引只负责导航，不替代正文验收边界。

规则：

- 只有需求、测试和验收命令都完成后才创建“完成”记录。
- 失败尝试只保留有诊断价值的根因，不把最终已修复的普通编译错误堆成长日志。
- 记录引用真实文件或报告；不能用计划、代码存在或 CI 配置代替执行结果。
- 每次完成后同步更新 `docs/development/status.md`，再开始下一 HXA。
- HXA 完成后才发现的缺陷先按 [Bug 修复记录约定](../bug-fixes/README.md)判断：只有跨任务长期根因/不变式才单独建 Bug Fix；普通后续修复归新的 HXA/测试/evidence，不把大段修复史反向堆入旧交付快照。
- M0 的 HXA-001～003 已合并记录在 [M0 完成记录](M0.md)，不重复拆分。
