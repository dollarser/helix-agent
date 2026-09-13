"""Preserve Markdown hard breaks while removing trailing whitespace in the imported plan."""
from pathlib import Path

root = Path(__file__).resolve().parents[3]
path = root / "docs/helix-agent-complete-research-and-product-plan.md"
lines = path.read_text().splitlines()
path.write_text("\n".join(
    line.rstrip() + ("\\" if line.endswith("  ") and line.strip() else "")
    for line in lines
) + "\n")
