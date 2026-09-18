"""Resolve only inspected additive resources and known documentation conflicts."""
from pathlib import Path
import re
import subprocess

root = Path.cwd()
pattern = re.compile(r'^<<<<<<< HEAD\n(.*?)^=======\n(.*?)^>>>>>>> codex/batch-b-recovery-readiness\n', re.M | re.S)
for folder in ('values', 'values-en', 'values-zh-rCN'):
    path = root / 'app/src/main/res' / folder / 'strings.xml'
    text, count = pattern.subn(lambda m: m[1] + m[2], path.read_text())
    assert count == 1
    path.write_text(text)
for name in ('architecture/terminal.md', 'research/execution-engine-comparison.md'):
    path = root / 'docs' / name
    text, count = pattern.subn(lambda m: m[1], path.read_text())
    assert count == 1
    text = text.replace('../development/tasks/HXA-205.md', '../completion-records/HXA-205.md')
    text = text.replace('../development/tasks/HXA-204.md', '../completion-records/HXA-204.md')
    text = text.replace('- 下一批 [HXA-204]', '- 已交付 [HXA-204]')
    path.write_text(text)
path = root / 'docs/development/status.md'
text = subprocess.check_output(['git', 'show', 'HEAD:docs/development/status.md'], text=True)
text = text.replace('18 项未闭合义务分为：2 项收尾验收、7 项待实现', '16 项未闭合义务分为：2 项收尾验收、5 项待实现')
text = text.replace('下一活动 checkpoint 为批次B HXA-204 的跨执行域错误与恢复交互（205随后）', '批次B HXA-204/205 与 HXA-195 已交付，下一候选任务为 HXA-207（尚未启动）')
start = text.index('- **活动 checkpoint：')
end = text.index('- **执行环境后续**', start)
text = text[:start] + '- **下一候选任务：HXA-207 扩展添加到使用闭环**，随后191深色主题剩余部分与206核心集成验收；本次只整合，不自动开始开发。193、195、204、205均已交付，各任务证据与范围见完成记录。\n' + text[end:]
text = text.replace('本轮独立交付：', '本轮整合交付：HXA-204跨执行域恢复与HXA-205首次准备已交付（见[204记录](../completion-records/HXA-204.md)、[205记录](../completion-records/HXA-205.md)）；')
start = text.index('本轮并行所有权：')
text = text[:start] + '本轮整合：Codex的193/195与Claude Code的204/205合入本地main；未推送或发布。分支原有证据与本次合并后验证分开记录；Claude工作区未提交脚本原样保留。\n'
path.write_text(text)
path = root / 'docs/development/roadmap.md'
text = path.read_text().replace('18项未闭合义务：2项收尾验收、7项待实现', '16项未闭合义务：2项收尾验收、5项待实现')
text = text.replace('193配套收尾', '193已收尾').replace('下一批为C（195→207→191）', '下一批为C剩余部分（207→191；195已交付）')
path.write_text(text)
path = root / 'docs/development/implementation-guide.md'
text = path.read_text().replace('195→207→191', '207→191（195已交付）').replace('tasks/HXA-195.md', 'tasks/HXA-207.md')
text = text.replace('193的干净资产/升级/CI仍单独收尾，不重做打包', '193默认锁定资产准备与升级已收尾，显式旧包镜像重建边界见完成记录，不重做打包')
path.write_text(text)
for number in ('204', '205'):
    path = root / f'docs/completion-records/HXA-{number}.md'
    original = subprocess.check_output(['git', 'show', f'HEAD:docs/development/tasks/HXA-{number}.md'], text=True)
    principle = original.split('## 范围与验收要求\n\n', 1)[1].split('\n\n', 1)[0]
    principle = principle.replace('../implementation-guide.md', '../development/implementation-guide.md')
    path.write_text(path.read_text() + '\n## 整合保留的能力边界\n\n' + principle + '\n')
