"""Archive the reviewed main research snapshot into docs/research in both checkouts.

Run from the source checkout; optionally supply another checkout as the first argument.
Source links are pinned to the captured main revision, not the evolving Harness tree.
"""
import os
import re
import sys
from pathlib import Path

root = Path.cwd()
targets = [root, *[Path(arg).resolve() for arg in sys.argv[1:]]]
names = ["helix-agent-complete-research-and-product-plan.md", "helix-mermaid-architecture-diagrams.md"]
revision = "27b643e895591464d88ea71d48528635768bfd60"
texts = {}
for name in names:
    text = (root / "docs" / name).read_text()
    def relocate(match):
        dest = match.group(2)
        if dest.startswith(("https:", "http:", "#", "mailto:")):
            return match.group(0)
        path, sep, anchor = dest.partition("#")
        resolved = (root / "docs" / path).resolve()
        if path in names:
            new = path
        elif not resolved.is_relative_to(root / "docs"):
            new = f"https://github.com/dollarser/helix-agent/blob/{revision}/{resolved.relative_to(root)}"
        else:
            new = os.path.relpath(resolved, root / "docs/research")
        return f"{match.group(1)}({new}{sep}{anchor})"
    text = re.sub(r"(\[[^\]]*\])\(([^)]+)\)", relocate, text)
    note = ("\n> 2026-09-14 分类更新：两份材料统一归入 `docs/research/`。下文的“现状/本轮”均指 2026-09-13 的 main 研究快照，"
            "不是 Harness 分支的当前实现；源码链接固定到取证结束时的 main 修订。重构收尾以[专项交接](../development/harness-2.0-next-work.md)和[实施状态](../development/status.md)为准。\n")
    text = text.replace("\n\n## 1.", note + "\n## 1.", 1)
    if name == names[0]:
        old = next(line for line in text.splitlines() if line.startswith("两份文件按用户指定路径保留。"))
        text = text.replace(old, "2026-09-13 修订曾保留根目录原路径，导致分类门禁失败；2026-09-14 已按用户要求归入研究分类并修复引用。下表保留原轮次检查结果，归档后的检查见[专项交接](../development/harness-2.0-next-work.md)，不覆盖历史失败记录。")
    texts[name] = text

for target in targets:
    dest = target / "docs/research"
    dest.mkdir(exist_ok=True)
    for name, text in texts.items():
        (dest / name).write_text(text)
        (target / "docs" / name).unlink()
    index = target / "docs/README.md"
    text = index.read_text().replace("├── references/", "├── research/            研究快照、候选方案与非规范性图集\n├── references/", 1)
    entry = ("## 重构研究\n\n"
             "- [研究与产品演进候选方案](research/helix-agent-complete-research-and-product-plan.md)：事实复核、产品问题、职责契约与候选批次；不是实施授权。\n"
             "- [现状快照与候选演进图](research/helix-mermaid-architecture-diagrams.md)：配套调用、执行域、状态和恢复视图；不替代当前架构规范。\n"
             "- [Harness 2.0 收尾与小模型交接](development/harness-2.0-next-work.md)：已解决问题、技术取舍、执行顺序与验收要求。\n\n")
    index.write_text(text.replace("## 架构\n", entry + "## 架构\n", 1))
    historical_script = target / "scripts/debug/2026-09-13/normalize-research-whitespace.py"
    historical_script.write_text(historical_script.read_text().replace('docs/helix-agent-', 'docs/research/helix-agent-'))
