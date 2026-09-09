from pathlib import Path
old='0039-background-' + 'task' + '-results-and-goal-blockers.md'
new='0039-background-results-and-goal-blockers.md'
for root in ['docs','scripts/debug/2026-09-09']:
    for p in Path(root).rglob('*'):
        if p.is_file() and p.suffix in {'.md','.py'} and p.name != 'rename-background-decision.py':
            s=p.read_text()
            if old in s:
                p.write_text(s.replace(old,new))
Path('docs/adr',old).rename(Path('docs/adr',new))
