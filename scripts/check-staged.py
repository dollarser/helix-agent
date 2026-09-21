#!/usr/bin/env python3
"""Fast index-only feedback. Does not replace source, host, device or remote CI gates."""
from pathlib import Path
import shutil
import subprocess
import sys
import time

PATTERNS = Path(__file__).with_name("secret-pattern.txt")


def git(*args):
    return subprocess.check_output(["git", *args], stderr=subprocess.DEVNULL)


def main():
    started = time.monotonic()
    if not shutil.which("rg") or not PATTERNS.is_file() or not PATTERNS.read_bytes().strip():
        raise RuntimeError("ripgrep or secret patterns unavailable; refusing to pass")
    if git("ls-files", "--unmerged", "-z"):
        raise RuntimeError("unresolved index entries; refusing to pass")
    changed = set(git("diff", "--cached", "--name-only", "--no-renames", "--no-ext-diff",
                      "--no-textconv", "--diff-filter=ACMT", "-z").split(b"\0")) - {b""}
    count = 0
    for entry in git("ls-files", "--stage", "-z").split(b"\0"):
        if not entry:
            continue
        metadata, path = entry.split(b"\t", 1)
        if path not in changed:
            continue
        mode, oid, stage = metadata.split()
        if mode == b"160000":  # Only the gitlink is committed here; submodule content is separate.
            continue
        if stage != b"0" or mode not in {b"100644", b"100755", b"120000"}:
            raise RuntimeError("unsupported index entry; refusing to pass")
        # Read the staged blob, including binary bytes and symlink text; never follow a target.
        with subprocess.Popen(["git", "cat-file", "blob", oid.decode("ascii")],
                              stdout=subprocess.PIPE, stderr=subprocess.DEVNULL) as blob:
            with subprocess.Popen(["rg", "--pcre2", "--text", "--quiet", "--file", str(PATTERNS), "-"],
                                  stdin=blob.stdout, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL) as scan:
                blob.stdout.close()
                scan_code = scan.wait()
            blob_code = blob.wait()
        if scan_code == 0:
            # Never print matched bytes (including whitespace diagnostics containing a secret).
            raise RuntimeError(f"potential secret in staged path {path.decode(errors='backslashreplace')!r}; content suppressed")
        if scan_code != 1 or blob_code != 0:
            raise RuntimeError("blob reader or secret scanner failed; refusing to pass")
        count += 1
    whitespace = subprocess.run(["git", "diff", "--cached", "--check", "--no-ext-diff", "--no-textconv"],
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if whitespace.returncode:
        raise RuntimeError("staged whitespace/conflict-marker check failed; inspect the staged diff locally")
    elapsed = time.monotonic() - started
    print(f"Staged gate passed: {count} blobs, {elapsed:.3f}s; no Gradle or network.")
    if elapsed > 3:
        print("The usual 3s feedback target was exceeded; no checks were skipped.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, subprocess.CalledProcessError, RuntimeError) as error:
        print(f"check-staged: {error}", file=sys.stderr)
        raise SystemExit(1)
