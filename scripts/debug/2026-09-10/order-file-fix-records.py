"""Order existing bug-note sections without changing their content."""
from pathlib import Path
import re
order = ['Problem', 'Impact', 'Root cause', 'Fix and invariants', 'Alternatives considered',
         'Regression verification', 'Residual risk', 'Related records']
for filename in ['2026-09-10-act-history-recovery.md', '2026-09-10-file-task-context-and-approval.md']:
    path = Path('docs/bug-fixes') / filename
    parts = re.split(r'^## (.+)\n', path.read_text(), flags=re.M)
    sections = dict(zip(parts[1::2], parts[2::2]))
    extras = ''.join('\n### ' + k + '\n' + v for k, v in sections.items() if k not in order)
    sections['Fix and invariants'] += extras
    path.write_text(parts[0] + ''.join('## ' + k + '\n' + sections[k] for k in order))
