"""Bring this task's two existing fix notes into the repository bug-record format."""
from pathlib import Path
for filename, sections in [
 ('2026-09-10-act-history-recovery.md', {'已确认的问题': 'Problem', '修复': 'Fix and invariants', '验证': 'Regression verification'}),
 ('2026-09-10-file-task-context-and-approval.md', {'当前证据': 'Problem', '改动': 'Fix and invariants', '验证入口与范围': 'Regression verification'}),
]:
    path = Path('docs/bug-fixes') / filename
    text = path.read_text()
    title, rest = text.split('\n', 1)
    text = '# Bug Fix: ' + title.removeprefix('# ') + '\n\nStatus: fixed\nDate: 2026-09-10\nRelated HXA: HXA-190, HXA-191\n' + rest
    for before, after in sections.items():
        text = text.replace('## ' + before, '## ' + after)
    text += '''
## Impact

文件任务失败与历史构建错误会阻断后续对话；过多默认展开的信息影响审批和阅读。

## Root cause

见 Problem 中的当前设备证据：模型参数/工作区引用与 Harness 历史/工具契约不一致，不能将其统一归因于网络。

## Alternatives considered

清空会话、盲目重试或仅扩大预算不能修复错误契约；保留历史结果并提供一致的解析与反馈。

## Residual risk

真实模型完成率与设备交互仍由所有者人工验收。后续 ADR-0045 已将文件路径进一步改为会话相对路径，三目录前缀不再是普通文件的强制要求；本记录中的旧批次描述只代表当时交付。

## Related records

- [ADR-0045](../adr/0045-session-relative-file-tools.md)
- [当前状态](../development/status.md)
'''
    path.write_text(text)
