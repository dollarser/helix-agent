"""Render captured synthetic local-model requests and events into a readable session."""
import json
from pathlib import Path
import re
import sys

root = Path(sys.argv[1])
requests = re.split(r'(?m)^ModelRequest\(', (root / 'requests.txt').read_text())[1:]
rounds = (root / 'events.txt').read_text().split('START ')[1:]
assert len(requests) == len(rounds)
out = ['# HXA-222 真实会话回放', '',
       '来源：该目录的 requests.txt（Provider 输入）、events.txt（native 归一化输出）、trajectory.txt（Room 持久化事实）。',
       '仅包含隔离模拟器内的合成测试数据。不是模型隐藏思考过程，也不包含模板渲染后的原始 token 流。', '']
for index, (request, events) in enumerate(zip(requests, rounds)):
    phase = f'Probe {index + 1}' if index < 2 else f'任务第 {index - 1} 轮'
    out.extend([f'## {phase}', '', f'Generation: `{events.splitlines()[0]}`', ''])
    if index == 2:
        user = re.search(r'ModelMessage\(role=USER, text=(.*?), images=', request, re.S)
        out.extend(['### 用户请求', '', user.group(1), ''])
    tool_results = re.findall(r'ModelMessage\(role=TOOL, text=(.*?), images=\[\], toolCallId=([^,]+), toolName=([^,]+), toolCalls=\[\]\)', request, re.S)
    out.extend(['### 本轮收到的工具事实（历史也随请求回填）', ''])
    for content, call_id, tool in tool_results:
        out.extend([f'`{tool}` / `{call_id}`', '', '```text', content, '```', ''])
    out.extend(['### 本轮模型输出', '', '```text', events.split('\n', 1)[1].strip(), '```', ''])
out.extend(['## 最终文件', ''])
for name in ('totals.csv', 'report.md'):
    file = root / name
    if file.exists():
        out.extend([f'### {name}', '', '```text', file.read_text(), '```', ''])
(root / 'session-replay.md').write_text('\n'.join(out))
print(root / 'session-replay.md')
