"""One-off guarded documentation link relocation; never changes product code.

Capture before the explicit guarded renames, preview, then apply. State and
backups stay in ignored build/. Re-running after content edits is rejected.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import posixpath
import re
import subprocess
import tempfile
from urllib.parse import unquote

ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / 'build/docs-convergence-20260929-75506fbe'
MOVES = {
 'docs/architecture/agent-capability-refactor-plan-2026-09-28.md': 'docs/architecture/harness-refactor-plan.md',
 'docs/architecture/plugin-platform-refactor-2026-09-28.md': 'docs/architecture/plugin-platform-plan.md',
 'docs/development/remaining-work-plan-2026-09-28.md': 'docs/development/release-readiness.md',
 'docs/development/public-benchmark-full-run-plan-2026-09-29.md': 'docs/evidence/development/verification-plans/public-benchmark-full-run-plan-2026-09-29.md',
 'docs/references/goal-feature-guide.md': 'docs/references/deepseek-harness-goal.md',
 'docs/references/agent-image-reading.md': 'docs/product/image-reading.md',
 'docs/references/helix-linux-command-integration.md': 'docs/evidence/research-history/helix-linux-command-integration.md',
 'docs/references/background-task-completion-notify-mechanism.md': 'docs/evidence/research-history/background-task-completion-2026-09-10.md',
 'docs/research/agent-memory-and-activity-presentation-2026-09-26.md': 'docs/evidence/research-history/agent-memory-and-activity-presentation-2026-09-26.md',
 'docs/research/conversation-first-session-workbench-2026-09-26.md': 'docs/evidence/research-history/conversation-first-session-workbench-2026-09-26.md',
 'docs/research/workspace-competitive-contracts-2026-09-27.md': 'docs/evidence/research-history/workspace-competitive-contracts-2026-09-27.md',
 'docs/research/helix-agent-capability-architecture-convergence-2026-09-28.md': 'docs/evidence/research-history/helix-agent-capability-architecture-convergence-2026-09-28.md',
 'docs/research/agent-plugin-ecosystem-and-mobile-use-2026-09-28.md': 'docs/research/topics/plugin-ecosystem-and-mobile-use-2026-09-28.md',
 'docs/research/async-jobs-wait-and-background-execution-competitive-study-2026-09-28.md': 'docs/research/topics/async-jobs-and-background-execution-2026-09-28.md',
}
LINK = re.compile(r'(!?\[[^\]\n]*\]\()([^\)\n]+)(\))')
REF = re.compile(r'^(\s*\[[^\]\n]+\]:\s*)(\S+)(.*)$', re.MULTILINE)
SCHEME = re.compile(r'^[A-Za-z][A-Za-z0-9+.-]*:')


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def atomic_write(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, name = tempfile.mkstemp(dir=path.parent, prefix='.docs-relocate-')
    try:
        with os.fdopen(fd, 'wb') as stream:
            stream.write(data)
        os.replace(name, path)
    finally:
        if os.path.exists(name):
            os.unlink(name)


def capture() -> None:
    if OUT.exists():
        raise SystemExit('Capture already exists; do not overwrite the baseline.')
    docs = sorted({*ROOT.glob('*.md'), *(ROOT/'docs').rglob('*.md'), *(ROOT/'reviews').rglob('*.md')})
    hashes = {str(p.relative_to(ROOT)): digest(p.read_bytes()) for p in docs}
    for old, new in MOVES.items():
        if old not in hashes or (ROOT/new).exists():
            raise SystemExit(f'Missing source or occupied target: {old} -> {new}')
    OUT.mkdir(parents=True)
    for p in docs:
        dest = OUT/'before'/p.relative_to(ROOT)
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(p.read_bytes())
    changed = subprocess.check_output(['git','diff','--name-only'], cwd=ROOT, text=True).splitlines()
    protected = {p:digest((ROOT/p).read_bytes()) for p in changed if not p.startswith('docs/') and (ROOT/p).is_file()}
    state = {'head':subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),
             'moves':MOVES, 'hashes':hashes, 'protected_dirty_files':protected,
             'git_status':subprocess.check_output(['git','status','--short'],cwd=ROOT,text=True)}
    (OUT/'before.json').write_text(json.dumps(state,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({'captured_markdown':len(hashes),'renames':len(MOVES),'state':str(OUT.relative_to(ROOT))},ensure_ascii=False))


def relocate_target(raw: str, old: str, new: str) -> str:
    match = re.match(r'(<?)([^\s>]+)(>?)(.*)',raw)
    if not match:
        return raw
    left, token, right, suffix = match.groups()
    if token.startswith(('#','/')) or SCHEME.match(token):
        return raw
    body, sep, fragment = token.partition('#')
    path, question, query = body.partition('?')
    target = posixpath.normpath(posixpath.join(posixpath.dirname(old),unquote(path)))
    if target.startswith('../') or not ((ROOT/target).exists() or target in MOVES):
        return raw
    updated = MOVES.get(target,target)
    if old == new and updated == target:
        return raw
    rel = posixpath.relpath(updated,posixpath.dirname(new))
    return left + rel + (question+query if question else '') + (sep+fragment if sep else '') + right + suffix


def rebase(text: str, old: str, new: str) -> str:
    text = LINK.sub(lambda m:m[1]+relocate_target(m[2],old,new)+m[3],text)
    text = REF.sub(lambda m:m[1]+relocate_target(m[2],old,new)+m[3],text)
    # Repository-relative code paths, not external URLs or relative Markdown links.
    for source,target in sorted(MOVES.items(),key=lambda x:-len(x[0])):
        text = re.sub(r'(?<![A-Za-z0-9_./-])'+re.escape(source)+r'(?![A-Za-z0-9_.-])',lambda _:target,text)
    return text


def rewrite(apply: bool) -> None:
    state=json.loads((OUT/'before.json').read_text())
    plans=[]
    for old,expected in state['hashes'].items():
        new=MOVES.get(old,old); p=ROOT/new
        if not p.is_file() or digest(p.read_bytes())!=expected:
            raise SystemExit(f'Source changed since capture: {new}; reread and reconcile.')
        data=p.read_bytes(); edited=rebase(data.decode('utf-8'),old,new).encode('utf-8')
        if edited!=data:
            plans.append((p,data,edited))
    for old,new in MOVES.items():
        if (ROOT/old).exists() or not (ROOT/new).is_file():
            raise SystemExit(f'Rename not complete: {old}')
    if apply:
        # Preflight all files, then check once more immediately before each write.
        for p,data,edited in plans:
            if p.read_bytes()!=data:
                raise SystemExit(f'Concurrent edit: {p.relative_to(ROOT)}')
            atomic_write(p,edited)
        (OUT/'link-rewrites.json').write_text(json.dumps({'files':[str(p.relative_to(ROOT)) for p,_,_ in plans]},ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({'applied':apply,'files':len(plans),'paths':[str(p.relative_to(ROOT)) for p,_,_ in plans]},ensure_ascii=False,indent=2))


def main() -> None:
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--capture',action='store_true')
    parser.add_argument('--apply',action='store_true')
    args=parser.parse_args()
    if args.capture and args.apply:
        parser.error('Capture and apply are separate operations.')
    capture() if args.capture else rewrite(args.apply)


if __name__=='__main__':
    main()
