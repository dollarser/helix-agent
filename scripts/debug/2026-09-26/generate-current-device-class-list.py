#!/usr/bin/env python3
"""Generate a complete, deduplicated manifest of androidTest classes from source.

Scans app/src/androidTest/kotlin for top-level, non-abstract classes that declare
at least one @Test method. Properly discovers multiple test classes in a single file
(e.g. GoalModelReportFlowDeviceTest, RunControlUiDeviceTest).
"""

import argparse
import datetime
import hashlib
import json
import os
import re
import subprocess
import sys


def strip_kotlin_code(text: str) -> str:
    """Strips comments, string literals, and char literals while preserving newlines and braces."""
    out = []
    i = 0
    n = len(text)
    while i < n:
        # Line comments
        if i + 1 < n and text[i:i + 2] == "//":
            j = text.find("\n", i + 2)
            if j == -1:
                j = n
            out.append(" " * (j - i))
            i = j
        # Block comments
        elif i + 1 < n and text[i:i + 2] == "/*":
            j = text.find("*/", i + 2)
            if j == -1:
                j = n - 2
            comment = text[i:j + 2]
            out.append("".join("\n" if c == "\n" else " " for c in comment))
            i = j + 2
        # Triple-quoted raw strings
        elif i + 2 < n and text[i:i + 3] == '"""':
            j = text.find('"""', i + 3)
            if j == -1:
                j = n - 3
            raw_str = text[i:j + 3]
            out.append("".join("\n" if c == "\n" else " " for c in raw_str))
            i = j + 3
        # Single-quoted string literals
        elif text[i] == '"':
            j = i + 1
            while j < n and text[j] != '"':
                if text[j] == "\\":
                    j += 2
                elif text[j] == "\n":
                    break
                else:
                    j += 1
            if j < n and text[j] == '"':
                j += 1
            out.append(" " * (j - i))
            i = j
        # Char literals
        elif text[i] == "'":
            j = i + 1
            while j < n and text[j] != "'":
                if text[j] == "\\":
                    j += 2
                elif text[j] == "\n":
                    break
                else:
                    j += 1
            if j < n and text[j] == "'":
                j += 1
            out.append(" " * (j - i))
            i = j
        else:
            out.append(text[i])
            i += 1
    return "".join(out)


def discover_test_classes(source_root: str):
    """Finds all non-abstract top-level classes containing @Test."""
    discovered = set()
    file_map = {}

    for root, _, files in os.walk(source_root):
        for f in files:
            if not f.endswith(".kt"):
                continue
            path = os.path.join(root, f)
            with open(path, "r", encoding="utf-8") as fp:
                raw_content = fp.read()

            pkg_m = re.search(r"^\s*package\s+([\w\.]+)", raw_content, re.MULTILINE)
            pkg = pkg_m.group(1) if pkg_m else ""

            cleaned = strip_kotlin_code(raw_content)

            # Match braces, @Test, and class declarations
            tokens = re.finditer(
                r"([{}])|(@Test\b)|\b(abstract\s+class|sealed\s+class|class\s+([A-Za-z0-9_]+))",
                cleaned,
            )

            cur_depth = 0
            curr_class = None
            curr_class_has_test = False
            curr_class_is_candidate = False
            class_depth = 0
            classes_in_file = []

            for tm in tokens:
                brace = tm.group(1)
                at_test = tm.group(2)
                class_name = tm.group(4)

                if brace == "{":
                    cur_depth += 1
                elif brace == "}":
                    cur_depth -= 1
                    if curr_class and cur_depth < class_depth:
                        if curr_class_has_test and curr_class_is_candidate:
                            full_name = f"{pkg}.{curr_class}" if pkg else curr_class
                            discovered.add(full_name)
                            classes_in_file.append(full_name)
                        curr_class = None
                        curr_class_has_test = False
                        curr_class_is_candidate = False
                elif class_name and cur_depth == 0:
                    curr_class = class_name
                    curr_class_is_candidate = True
                    curr_class_has_test = False
                    class_depth = cur_depth + 1
                elif at_test and curr_class:
                    curr_class_has_test = True

            if classes_in_file:
                file_map[path] = classes_in_file

    sorted_classes = sorted(discovered)
    return sorted_classes, file_map


def get_git_commit(repo_root: str) -> str:
    try:
        out = subprocess.check_output(
            ["git", "rev-parse", "HEAD"],
            cwd=repo_root,
            text=True,
        ).strip()
        return out
    except Exception:
        return "UNKNOWN"


def main():
    parser = argparse.ArgumentParser(description="Generate androidTest class manifest")
    parser.add_argument(
        "--source-root",
        default="app/src/androidTest/kotlin",
        help="Root directory of androidTest source code",
    )
    parser.add_argument(
        "--output-classes",
        default="scripts/debug/2026-09-26/current-consumer-device-classes.txt",
        help="Path to write line-separated test classes",
    )
    parser.add_argument(
        "--output-manifest",
        default="scripts/debug/2026-09-26/current-device-manifest.json",
        help="Path to write manifest JSON",
    )
    args = parser.parse_args()

    repo_root = os.path.abspath(os.path.join(os.path.dirname(__file__), "../../.."))
    source_root = os.path.abspath(args.source_root)
    git_commit = get_git_commit(repo_root)

    classes, file_map = discover_test_classes(source_root)
    class_count = len(classes)

    class_list_text = "\n".join(classes) + "\n"
    class_list_sha256 = hashlib.sha256(class_list_text.encode("utf-8")).hexdigest()

    manifest = {
        "git_commit": git_commit,
        "source_root": os.path.relpath(source_root, repo_root),
        "class_count": class_count,
        "class_list_sha256": class_list_sha256,
        "generated_at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "classes": classes,
    }

    if args.output_classes:
        out_cls_path = os.path.abspath(args.output_classes)
        os.makedirs(os.path.dirname(out_cls_path), exist_ok=True)
        with open(out_cls_path, "w", encoding="utf-8") as fp:
            fp.write(class_list_text)
        print(f"Wrote {class_count} classes to {out_cls_path}")

    if args.output_manifest:
        out_man_path = os.path.abspath(args.output_manifest)
        os.makedirs(os.path.dirname(out_man_path), exist_ok=True)
        with open(out_man_path, "w", encoding="utf-8") as fp:
            json.dump(manifest, fp, indent=2)
        print(f"Wrote manifest (SHA-256: {class_list_sha256}) to {out_man_path}")

    print(f"Commit: {git_commit}")
    print(f"Class count: {class_count}")
    print(f"Class list SHA-256: {class_list_sha256}")


if __name__ == "__main__":
    main()
