"""Add localized compact tool labels."""
from pathlib import Path
labels = {
'tool_details_show': ('查看详情','Details'), 'tool_details_hide': ('收起详情','Hide details'),
'tool_purpose_write': ('写入文件','Write file'), 'tool_purpose_edit': ('修改文件','Edit file'),
'tool_purpose_read': ('读取内容','Read content'), 'tool_purpose_inspect': ('查询信息','Inspect information'),
'tool_purpose_search': ('查找内容','Find content'), 'tool_purpose_interact': ('操作或查看界面','Inspect or interact with UI'),
'tool_purpose_run': ('执行代码','Run code'), 'tool_purpose_execute': ('执行操作','Perform operation')}
for folder in ['values','values-zh-rCN','values-en']:
    p=Path('app/src/main/res')/folder/'strings.xml';s=p.read_text()
    rows=''.join(f'    <string name="{k}">{v[1 if folder=="values-en" else 0]}</string>\n' for k,v in labels.items() if f'name="{k}"' not in s)
    p.write_text(s.replace('</resources>',rows+'</resources>'))
