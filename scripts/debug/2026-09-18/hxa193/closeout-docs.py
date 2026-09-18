"""Record this fixed-source closeout only after both owned runtime runs pass."""
from pathlib import Path
import json
import re

root = Path.cwd()
for api in ('api29', 'api36-functional'):
    evidence = root / 'build/193-closeout' / api
    assert 'OK (35 tests)' in (evidence / 'instrumentation.txt').read_text()
    assert json.loads((evidence / 'closed.json').read_text())['exit'] == 0
assert 'Subscription managed account entry' in (root / 'build/193-closeout/host-all.log').read_text()
for path in (root / 'docs').rglob('*.md'):
    before = path.read_text()
    after = re.sub(r'(\]\([^)]*)development/tasks/HXA-193\.md\)', r'\1completion-records/HXA-193.md)', before)
    if after != before:
        path.write_text(after)
path = root / 'docs/development/roadmap.md'
s = path.read_text().replace('20项未闭合义务：3项收尾验收', '19项未闭合义务：2项收尾验收')
s = s.replace('| HXA-193 | 收尾验收 | 包内 Runtime 可复现资产与升级验收 | [任务规格](tasks/HXA-193.md) |', '| HXA-193 | 已交付 | 包内 Runtime 可复现资产与升级验收 | [交付证据](../completion-records/HXA-193.md) |')
path.write_text(s)
path = root / 'docs/development/status.md'
s = path.read_text().replace('20 项未闭合义务分为：3 项收尾验收', '19 项未闭合义务分为：2 项收尾验收')
s = s.replace('默认镜像重建边界仍未闭合，不标整体完成。', '默认入口已改用固定归档并通过双 API Runtime 与完整主机门禁，见[193完成记录](../completion-records/HXA-193.md)；显式旧包镜像重建仍失败，不作为通过项。')
s = s.replace('仍有默认镜像重建边界，见', '默认资产准备已收尾；显式旧包重建的供应边界仍保留，见')
s = s.replace('（205随后，193配套收尾）', '（205随后）；193已完成当前锁定资产准备与升级验收')
s = s.replace('（193配套收尾）', '（193已收尾）')
s += '\n本轮并行所有权：Claude Code 继续204→205；Codex在193收尾后推进195，独立worktree，先Runtime日志协议再详情展示，不重写204恢复逻辑。\n'
path.write_text(s)
(root / 'docs/development/tasks/HXA-193.md').unlink()
