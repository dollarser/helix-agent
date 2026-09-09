from pathlib import Path
p=Path('docs/development/status.md');s=p.read_text().replace('HXA-180 正在完善独立文件管理，HXA-181 同步清理过时规范', 'HXA-180 独立文件管理与 HXA-181 规范清理也已完成');a=s.index('## In progress');b=s.index('## Blocked',a);s=s[:a]+'''- M10 / HXA-180 已完成：手动 Workspace/共享存储/SAF 变更与恢复，主机2768项零失败（8既有条件跳过）、双 API 各18项及预览连续复跑通过，见 [完成记录](../completion-records/HXA-180.md)。
- M10 / HXA-181 已完成：当前规范与历史记录分离，文档/ADR/i18n/secrets/diff 通过，见 [完成记录](../completion-records/HXA-181.md)。

## In progress

无。本批 HXA-179～181 已完成，保留当前工作树，尚未提交、推送或合并 main。

## Next task

等待所有者选择下一项工作或提交范围；不自动启动新功能。长稳、受保护账号、Root 真机及发布门禁继续单列，不以模拟器或 fixture 替代。

'''+s[b:];p.write_text(s)
p=Path('docs/development/roadmap.md');s=p.read_text();a=s.index('### HXA-180');s=s[:a]+s[a:].replace('状态：in progress','状态：completed').replace('状态：planned','状态：completed');p.write_text(s)
p=Path('docs/development/verification-matrix.md');s=p.read_text().replace('| 进行中，当前状态见 status |','| 已完成，见 [HXA-180](../completion-records/HXA-180.md)：主机/双API各18/预览复跑 |',1).replace('| 进行中，当前状态见 status |','| 全部通过，见 [HXA-181](../completion-records/HXA-181.md) |',1);p.write_text(s)
p=Path('docs/adr/0041-manual-file-management-mutations.md');s=p.read_text().replace('具体实现与验收以完成记录为准。','实现与主机、双 API 验收已完成，见 [HXA-180](../completion-records/HXA-180.md)。');p.write_text(s)
