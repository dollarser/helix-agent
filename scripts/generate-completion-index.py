#!/usr/bin/env python3
"""Generate navigation from completion records; never infer product acceptance."""
import argparse
from pathlib import Path


def render(root: Path) -> str:
    records = sorted((root / "docs/completion-records").glob("HXA-[0-9][0-9][0-9].md"))
    lines = [
        "# 完成记录索引", "",
        "由 `scripts/generate-completion-index.py` 从记录标题生成，请勿手改。",
        "记录存在表示该检查点有交付证据；验收范围、跳过项及限制以记录正文为准，不能据此推断全量发布通过。",
        "HXA 编号可以不连续；历史预留或退出的任务不补造完成记录、不重新编号。", "",
        "- [M0：HXA-001～003](M0.md)", "",
        "| 任务 | 交付记录 |", "| --- | --- |",
    ]
    for record in records:
        title = next(line.lstrip("# ") for line in record.read_text().splitlines() if line.startswith("#"))
        lines.append(f"| {record.stem} | [{title.replace('|', '／')}]({record.name}) |")
    return "\n".join(lines) + "\n"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    path = root / "docs/completion-records/index.md"
    content = render(root)
    if args.check:
        if not path.is_file() or path.read_text() != content:
            parser.exit(1, "Completion index is stale; run scripts/generate-completion-index.py\n")
        print("Completion index verified")
    else:
        path.write_text(content)


if __name__ == "__main__":
    main()
