#!/usr/bin/env python3
"""Fetch pinned public config evidence into ignored build output; never run plugin code."""
import hashlib
import json
from pathlib import Path
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / 'app/build/outputs/hxa-125-samples'
SOURCES = {
    'anthropic-linear.json': ('anthropics/claude-plugins-official', '85cce0381e7860082641b59d961a2b8c368b8b79', 'external_plugins/linear/.mcp.json'),
    'anthropic-github.json': ('anthropics/claude-plugins-official', '85cce0381e7860082641b59d961a2b8c368b8b79', 'external_plugins/github/.mcp.json'),
    'anthropic-playwright.json': ('anthropics/claude-plugins-official', '85cce0381e7860082641b59d961a2b8c368b8b79', 'external_plugins/playwright/.mcp.json'),
    'cloudflare-mcp.json': ('cloudflare/skills', 'b8aeca6d7e2d614d7bd0e5220c8dd7645fe58a93', '.mcp.json'),
    'cloudflare-codex.json': ('cloudflare/skills', 'b8aeca6d7e2d614d7bd0e5220c8dd7645fe58a93', '.codex-plugin/plugin.json'),
    'cloudflare-claude.json': ('cloudflare/skills', 'b8aeca6d7e2d614d7bd0e5220c8dd7645fe58a93', '.claude-plugin/plugin.json'),
}


EXPECTED_SHA256 = {'anthropic-linear.json': '60bc954e5c2018171f5efa358ddbfa6062a63b456f8d7fa567fd926910030e9f', 'anthropic-github.json': 'b536ea03380d2f2f93f31c87a158e21e74cce5f664d20f7bdb81c6710418e06d', 'anthropic-playwright.json': 'b6fd7e9ccee1682af353854195516826da6970026b68c86ad800ff4d931d3250', 'cloudflare-mcp.json': '6608aeaa3ce8be52077c96271b9da647683f12f42c51035d1107e5915194b690', 'cloudflare-codex.json': '0bad09fc15347fc5e9e918e734ce12b2a149584eb61826a1e068f3cb9526379f', 'cloudflare-claude.json': '43ed8be9f2942a7fba98501bc47e37fcf337cf57707b0912d593d8e85fcbafcf'}

def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    evidence = []
    for name, (repo, commit, path) in SOURCES.items():
        url = f'https://raw.githubusercontent.com/{repo}/{commit}/{path}'
        with urllib.request.urlopen(url, timeout=30) as response:
            data = response.read(256 * 1024 + 1)
        if len(data) > 256 * 1024:
            raise ValueError('public sample exceeds limit')
        if hashlib.sha256(data).hexdigest() != EXPECTED_SHA256[name]:
            raise ValueError(f"public sample hash mismatch: {name}")
        json.loads(data)
        (OUTPUT / name).write_bytes(data)
        evidence.append(dict(file=name, url=url, sha256=hashlib.sha256(data).hexdigest(), bytes=len(data)))
    (OUTPUT / 'manifest.json').write_text(json.dumps(evidence, indent=2) + '\n')
    print(json.dumps(evidence, indent=2))


if __name__ == '__main__':
    main()
