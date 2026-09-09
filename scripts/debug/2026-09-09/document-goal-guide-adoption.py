from pathlib import Path
p=Path('docs/development/verification-matrix.md')
s=p.read_text();s+='\n| HXA-177 | 跨会话后台/服务、精确停止、回收持久化、BLOCKED 门控/预算/恢复、数据库迁移 | 进行中，最终证据待录入 |\n';p.write_text(s)
p=Path('docs/adr/0039-background-results-and-goal-blockers.md')
s=p.read_text().replace('任务回收保留原消息与工具结果。','任务回收保留原消息与工具结果。未回收结果不受最近历史数量限制；已回收列表保留最近任务窗口，较早正文仍在原会话。')
s=s.replace('三轮门槛用于主观阻塞报告，不应用于已证实的预算/容量/未知副作用。','参考文档的三轮门槛针对主观阻塞报告；Helix 本次没有新增模型报告 blocked 的工具，不把此门槛应用于已证实的预算/容量/未知副作用。')
s=s.replace('并核对 DeepSeek 当前官方 goal-round-driver 文档。','并核对 DeepSeek 当前官方 [goal-round-driver 文档](https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/goal/goal-round-driver/README.md)。用户指南是指定历史版本的个人参考，不冒充上游规范或 Helix 的实现证据。')
p.write_text(s)
p=Path('scripts/debug/README.md');s=p.read_text();s+='\nHXA-177 one-off implementation scripts in `2026-09-09/` are retained for provenance,\nnot supported migrations to rerun. `run-owned-emulator.py` is the reusable runner;\n`hxa177-*` output folders contain failed and successful frozen attempts separately.\n';p.write_text(s)
