from pathlib import Path
p=Path('docs/development/status.md');s=p.read_text();a=s.index('更新时间：');b=s.index('| 维度 |');s=s[:a]+'''更新时间：2026-09-09（当前工作树；未合并 main）

## Current summary

当前实现与本分支状态：HXA-161～178 的会话/文件界面、上下文压缩、非阻塞后台任务及模型判断 Goal 完成已交付，HXA-179 职责拆分已验证。HXA-180 正在完善独立文件管理，HXA-181 同步清理过时规范；这一批仍未提交、合入 main 或推送。下面按 HXA 保存的验收记录是各自提交/工作树时点的证据，不代表 main 已包含后续实现。

Goal 完成采用 [ADR-0040](../adr/0040-model-judged-goal-completion.md)：模型提交结构化报告，Harness 处理有效性、运行状态和结算；ADR-0028 的强制条件绑定及独立 verifier 已取代。后台任务实现的是有界工具任务列表和结果回收，不能据此宣称子 Agent 或声明式 Workflow 已实现。

系统 JNI/Binder 根因、长稳、完整真机与商店发布门禁仍开放；应用层规避不等于修复 Android 系统。受保护账号、Root 真机和签名发行的外部条件见下方 Blocked。历史 main 验证见 [main 验证报告](main-merged-verification.md)，后置项目见 [优化待办](main-optimization-todo.md)。当前所有者授权的是 HXA-179～181，不自动启动其他新功能候选。

## 历史验收索引

下列里程碑及完成记录保留当时的范围、命令与结果；如机制被新 ADR 取代，以 Current summary 和最新对应完成记录为当前入口。

'''+s[b:];a=s.index('## In progress');b=s.index('## Blocked',a);s=s[:a]+'''- M10 / HXA-179 已完成：ChatService 职责提取，完整主机门禁与 API29/36 各107项回归通过，见 [完成记录](../completion-records/HXA-179.md)。

## In progress

HXA-180：独立文件管理变更及失败恢复验证。HXA-181：清理当前规范中的历史描述。保留工作树，不合并 main。

## Next task

完成本批 HXA-180/181 后保持等待；其他新功能不自动启动。长稳、受保护账号、Root 真机及发布门禁继续单列，不以模拟器或 fixture 替代。

'''+s[b:];p.write_text(s)
p=Path('docs/development/roadmap.md');s=p.read_text();a=s.index('### HXA-179');b=s.index('### HXA-180',a);part=s[a:b].replace('状态：in progress', '状态：completed').replace('状态：planned', '状态：completed');s=s[:a]+part+s[b:];a=s.index('### HXA-180');b=s.index('### HXA-181',a);s=s[:a]+s[a:b].replace('状态：planned','状态：in progress')+s[b:];p.write_text(s)
p=Path('AGENTS.md');s=p.read_text().replace('reads proposed `docs/adr/0008-git-workspace-management.md`','reads accepted `docs/adr/0008-git-workspace-management.md`');s=s.replace('until HXA-088/ADR-0008 decides persistent repository ownership.', 'outside the separately scoped persistent Git implementation authorized by accepted ADR-0008.');p.write_text(s)
p=Path('docs/architecture/provider-mcp-skills-modes.md');s=p.read_text().replace('且在 ADR-0009 接受前不可用','且须通过已接受 ADR-0009 的生产启用门禁后才可用');p.write_text(s)
p=Path('docs/development/main-optimization-todo.md');s=p.read_text();pos=s.index('\n');s=s[:pos]+'''\n
> 历史执行账本：下列勾选项、待办和追加日志保留各自时点。当前开发状态以 [status](status.md) 为准；Goal 完成机制已由 [ADR-0040](../adr/0040-model-judged-goal-completion.md) 取代 ADR-0028，旧“绑定/复核/完成证据仍待接通”不是当前任务。后续工作树提交与合并状态不能从这里的历史“已推送”或“未提交”推断。
'''+s[pos:];p.write_text(s)
p=Path('docs/adr/0036-manual-shared-storage-root.md');s=p.read_text()+'''\n## 后续扩展

[ADR-0041](0041-manual-file-management-mutations.md) 基于所有者的新授权扩展首轮只读能力；本记录保留共享根目录和 Agent scope 隔离决定及 HXA-173 的历史验收范围。
''';p.write_text(s)
