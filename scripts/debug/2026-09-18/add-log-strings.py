"""Historical HXA-195 first-draft resource edit, before lint-driven reuse of the old running-note key.

Kept as edit provenance; the delivered resources are authoritative, not this one-shot helper.
"""
from pathlib import Path
for folder, values in {
    'values': ('实时输出为有界预览；最终结果以命令结束后的记录为准。', '实时输出暂不可用；已有预览保留，命令状态与最终结果单独更新。'),
    'values-en': ('Live output is a bounded preview. The completed command record is authoritative.', 'Live output is unavailable. The preview is retained; command status and final results update separately.'),
    'values-zh-rCN': ('实时输出为有界预览；最终结果以命令结束后的记录为准。', '实时输出暂不可用；已有预览保留，命令状态与最终结果单独更新。'),
}.items():
    path = Path('app/src/main/res') / folder / 'strings.xml'
    text = path.read_text()
    assert 'name="command_log_running_note"' not in text
    rows = ''.join(f'    <string name="{key}">{value}</string>\n' for key, value in zip(('command_log_running_note', 'command_log_unavailable'), values))
    path.write_text(text.replace('</resources>', rows + '</resources>'))
