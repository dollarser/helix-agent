#!/usr/bin/env bash
set -euo pipefail

# HXA-069 (roadmap §10): the CI-runnable internationalization gate. Two independent checks:
#
#   1. Translation-key parity — the base fallback (values/strings.xml) and the two locale
#      resources (values-en/strings.xml, values-zh-rCN/strings.xml) must define the EXACT same
#      set of <string>/<plurals>/<string-array> keys. A missing key in a locale (or an extra key
#      only a locale has) is a fail-closed error: a user in that locale would otherwise see a
#      resource-id crash or a silently-stale fallback.
#
#   2. No hardcoded user-visible CJK string literals — a small Kotlin string-literal scanner
#      (a character state machine that tracks line/block comments, "…" and """…""" strings and
#      '…' chars) flags any CJK (Han) character that appears inside a STRING LITERAL in
#      production source. Comments are deliberately ignored (developer-facing, not user-visible);
#      tests are excluded (fixture text is not shipped UI). After the HXA-069 migration every
#      user-visible string is a stringResource(R.string.*) reference (no CJK literal), so this
#      check passes and then guards against any NEW hardcoded CJK regressing in.
#
# Content that must NEVER be translated (Tool/schema names, Provider model IDs, URLs, protocol
# fields, audit types, stable error codes, Locale.ROOT normalization) is ASCII by construction
# and therefore invisible to the CJK scan — no allowlist entry is needed for it. If a genuinely
# non-translatable CJK literal must remain, add it to ALLOWLIST below with a one-line reason.

readonly project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export HELIX_PROJECT_ROOT="$project_root"

python3 <<'PY'
from __future__ import annotations

import os
import re
import sys
from pathlib import Path

root = Path(os.environ["HELIX_PROJECT_ROOT"]).resolve()
errors: list[str] = []

# (relative-path substring, literal substring, reason) — a CJK string literal is allowed only
# when BOTH the path and the literal match an entry. An empty literal substring ("") allowlists
# the whole file ("" in literal is always true) — used for files that are pure model/protocol
# content, never UI, where every string must stay byte-stable across locales.
ALLOWLIST: list[tuple[str, str, str]] = [
    # Model-visible attachment injection (ADR-0014 §2/§4, HXA-049/055): the block labels and the
    # UNTRUSTED_MARKER are written into the model's user message and must be byte-stable across
    # locales so `model-visible ⇔ persisted` holds on history reconstruction. Translating them
    # would change the model request with the UI language.
    ("chat/AttachmentContext.kt", "", "model-visible attachment injection; byte-stable across locales"),
    # Fixed, versioned DOM-extraction JS for browser.snapshot (SCRIPT_VERSION, pinned by
    # BrowserSnapshotScriptTest). The only CJK is a JS comment inside the versioned constant; the
    # script bytes must not change per locale.
    ("snapshot/BrowserSnapshotScript.kt", "", "fixed versioned pinned JS script; locale-independent"),
]

# ── Check 1: translation-key parity (every module that ships a base fallback) ──────
# Resources are per-module (each has its own namespaced R), so EVERY module with a base
# values/strings.xml must keep values-en and values-zh-rCN in exact key parity (roadmap:
# complete fallback + values-en + values-zh-rCN in every locale). A module with no base
# strings.xml ships no user-visible strings — nothing to check there.
KEY_RE = re.compile(r"<(?:string|plurals|string-array)\s+name=\"([^\"]+)\"")


def resource_keys(path: Path) -> set[str]:
    return set(KEY_RE.findall(path.read_text(encoding="utf-8")))


total_keys = 0
base_files = sorted(
    p
    for p in root.glob("**/src/main/res/values/strings.xml")
    if "/build/" not in p.as_posix() and "/.claude/" not in p.as_posix()
)
for base_file in base_files:
    res_root = base_file.parent.parent  # <module>/src/main/res
    rel_root = res_root.relative_to(root).as_posix()
    base_keys = resource_keys(base_file)
    total_keys += len(base_keys)
    for locale in ("values-en", "values-zh-rCN"):
        locale_file = res_root / locale / "strings.xml"
        if not locale_file.is_file():
            errors.append(f"i18n: {locale}/strings.xml missing for module with base {rel_root}/values/strings.xml")
            continue
        locale_keys = resource_keys(locale_file)
        missing = sorted(base_keys - locale_keys)
        extra = sorted(locale_keys - base_keys)
        if missing:
            errors.append(f"i18n: keys missing from {rel_root}/{locale}: {missing}")
        if extra:
            errors.append(f"i18n: keys in {rel_root}/{locale} absent from base: {extra}")

# ── Check 2: no hardcoded CJK string literals in production source ────────────────
# CJK Unified Ideographs + Extension A + Compatibility Ideographs.
HAN = re.compile(r"[㐀-䶿一-鿿豈-﫿]")


def extract_string_literals(text: str) -> list[tuple[int, str]]:
    """Return (start_line, content) for each Kotlin string/char literal, ignoring comments."""
    literals: list[tuple[int, str]] = []
    i, n, line = 0, len(text), 1
    state = "code"
    block_depth = 0
    start_line = 0
    buf: list[str] = []

    while i < n:
        c = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if state == "code":
            if c == "\n":
                line += 1
            elif c == "/" and nxt == "/":
                state, i = "line", i + 2
                continue
            elif c == "/" and nxt == "*":
                state, block_depth, i = "block", 1, i + 2
                continue
            elif c == '"':
                if text.startswith('"""', i):
                    state, start_line, buf, i = "raw", line, [], i + 3
                else:
                    state, start_line, buf, i = "str", line, [], i + 1
                continue
            elif c == "'":
                state, start_line, buf, i = "char", line, [], i + 1
                continue
            i += 1
            continue
        if state == "line":
            if c == "\n":
                line += 1
                state = "code"
            i += 1
            continue
        if state == "block":
            if c == "\n":
                line += 1
            elif c == "/" and nxt == "*":
                block_depth += 1
                i += 2
                continue
            elif c == "*" and nxt == "/":
                block_depth -= 1
                i += 2
                if block_depth == 0:
                    state = "code"
                continue
            i += 1
            continue
        # str / raw / char: accumulate literal content
        if c == "\n":
            line += 1
        if c == "\\" and state in ("str", "char") and nxt:
            buf.extend((c, nxt))
            i += 2
            continue
        closing = (state == "raw" and text.startswith('"""', i)) or (
            state in ("str", "char") and c in ('"', "'")
        )
        if closing:
            literals.append((start_line, "".join(buf)))
            i += 3 if state == "raw" else 1
            state = "code"
            continue
        buf.append(c)
        i += 1
        continue
    return literals


def scan_files() -> list[Path]:
    files: list[Path] = []
    for pattern in (
        "app/src/*/kotlin/**/*.kt",
        "feature/*/src/*/kotlin/**/*.kt",
    ):
        files.extend(root.glob(pattern))
    seen: set[Path] = set()
    out: list[Path] = []
    for p in files:
        if p in seen:
            continue
        seen.add(p)
        rel = p.relative_to(root).as_posix()
        if "test" not in rel.lower():  # excludes /test/, androidTest*, *Test*.kt
            out.append(p)
    return sorted(out)


def allowed(rel: str, literal: str) -> bool:
    return any(pfx in rel and sub in literal for pfx, sub, _ in ALLOWLIST)


scanned = scan_files()
violations = 0
for path in scanned:
    rel = path.relative_to(root).as_posix()
    for start_line, content in extract_string_literals(path.read_text(encoding="utf-8")):
        if HAN.search(content) and not allowed(rel, content):
            excerpt = content.replace("\n", "\\n")
            if len(excerpt) > 48:
                excerpt = excerpt[:48] + "…"
            errors.append(f"i18n: hardcoded CJK string literal {rel}:{start_line}: {excerpt!r}")
            violations += 1

if errors:
    print("Internationalization verification failed:", file=sys.stderr)
    for error in errors:
        print(f"- {error}", file=sys.stderr)
    print(f"  ({len(scanned)} production source files scanned; {violations} hardcoded CJK literals)", file=sys.stderr)
    raise SystemExit(1)

print(
    "Internationalization verification passed "
    f"({len(scanned)} production source files scanned; "
    f"{total_keys} resource keys in parity across base/en/zh-rCN)."
)
PY
