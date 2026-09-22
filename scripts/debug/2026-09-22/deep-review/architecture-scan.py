"""Static declared module edges and app top-level package import cycles.

This is a source-declaration inventory, not Gradle variant resolution or a call graph.
"""
from pathlib import Path
import argparse
import json
import re

root = Path.cwd()
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output', type=Path, default=Path('build/deep-review/architecture.json'))
args = parser.parse_args()
out = args.output.resolve()
if not out.is_relative_to((root / 'build').resolve()) or out.exists():
    raise SystemExit('Choose a new output file inside ignored build/')
modules = re.findall(r'"(:[\w:-]+)"', (root / 'settings.gradle.kts').read_text())
edges = {m: set() for m in modules}
declarations = (root / 'build.gradle.kts').read_text().split('val projectDependencies =', 1)[1].split('subprojects {', 1)[0]
for module, body in re.findall(r'"(:[\w:-]+)"\s+to\s+listOf\((.*?)\)', declarations, re.S):
    edges[module].update(re.findall(r'"(:[\w:-]+)"', body))
for module in modules:
    path = root / module.lstrip(':').replace(':', '/') / 'build.gradle.kts'
    if path.exists():
        source = re.sub(r'//[^\n]*', '', path.read_text())
        edges[module].update(re.findall(r'project\("(:[\w:-]+)"\)', source))

def components(graph):
    index, low, stack, active, result = {}, {}, [], set(), []
    def visit(node):
        index[node] = low[node] = len(index)
        stack.append(node)
        active.add(node)
        for nxt in graph.get(node, set()):
            if nxt not in index:
                visit(nxt)
                low[node] = min(low[node], low[nxt])
            elif nxt in active:
                low[node] = min(low[node], index[nxt])
        if low[node] == index[node]:
            group = []
            while True:
                member = stack.pop()
                active.remove(member)
                group.append(member)
                if member == node:
                    break
            if len(group) > 1:
                result.append(sorted(group))
    for node in graph:
        if node not in index:
            visit(node)
    return result

packages = {}
for path in (root / 'app/src/main/kotlin').rglob('*.kt'):
    source = path.read_text()
    match = re.search(r'^package com\.helix\.app\.([\w]+)', source, re.M)
    if not match:
        continue
    origin = match.group(1)
    packages.setdefault(origin, set())
    packages[origin].update(p for p in re.findall(r'^import com\.helix\.app\.([\w]+)\.', source, re.M) if p != origin)

report = dict(module_count=len(modules), declared_edges={k: sorted(v) for k, v in edges.items()},
              module_cycles=components(edges), app_package_import_cycles=components(packages),
              core_edges_outside_core={k: sorted(v for v in vs if not v.startswith(':core:'))
                                       for k, vs in edges.items() if k.startswith(':core:') and any(not v.startswith(':core:') for v in vs)})
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps({k: v for k, v in report.items() if k != 'declared_edges'}, indent=2))
