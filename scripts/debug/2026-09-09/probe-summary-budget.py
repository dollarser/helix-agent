"""Keyless synthetic summary probe: retain usage and lengths, never reasoning text."""

import argparse
import json
from pathlib import Path
import time
import urllib.request

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--base-url", required=True)
parser.add_argument("--model", required=True)
parser.add_argument("--effort", default="low")
parser.add_argument("--tokens", type=int, default=2048)
parser.add_argument("--output", required=True)
args = parser.parse_args()
payload = {
    "model": args.model,
    "max_tokens": args.tokens,
    "reasoning_effort": args.effort,
    "messages": [{"role": "user", "content": (
        "Summarize as compact continuity notes with sections for goals, verified results, "
        "decisions, pending work, and exact references. Collapse repeated logs. "
        "History: Code ORANGE-42. Never delete original files. Verification remains pending. "
        + "Routine obsolete progress log; no further constraints. " * 150
    )}],
}
started = time.monotonic()
request = urllib.request.Request(
    args.base_url.rstrip("/") + "/chat/completions",
    data=json.dumps(payload).encode(),
    headers={"Content-Type": "application/json"},
)
with urllib.request.urlopen(request, timeout=120) as response:
    body = json.load(response)
choice = body["choices"][0]
message = choice["message"]
report = {
    "model": args.model,
    "effort": args.effort,
    "max_tokens": args.tokens,
    "finish_reason": choice["finish_reason"],
    "usage": body.get("usage"),
    "answer_chars": len(message.get("content") or ""),
    "reasoning_chars": len(message.get("reasoning_content") or ""),
    "elapsed_seconds": round(time.monotonic() - started, 2),
}
output = Path(args.output)
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(json.dumps(report, indent=2))
print(json.dumps(report))
