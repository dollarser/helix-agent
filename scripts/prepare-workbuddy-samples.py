"""Package the supplied marketplace subdirectories without changing their bytes.

Usage: python3 scripts/prepare-workbuddy-samples.py <export-directory> <output-directory>
Only market packages are included; account state and runtime configuration stay outside.
"""

import hashlib
import json
import sys
import zipfile
from pathlib import Path


def prepare(source, output):
    output.mkdir(parents=True, exist_ok=True)
    manifest = []
    for name in ("github", "kling-ai-plugin"):
        folder = source / "02-marketplace/connectors-marketplace/connectors" / name
        if not (folder / "mcp.json").is_file():
            raise ValueError("Missing marketplace package")
        target = output / (name + ".zip")
        with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as archive:
            for path in sorted(folder.rglob("*")):
                if path.is_symlink():
                    raise ValueError("Sample contains a symbolic link")
                if not path.is_file():
                    continue
                data = path.read_bytes()
                relative = path.relative_to(folder).as_posix()
                entry = zipfile.ZipInfo(relative, (2026, 9, 7, 0, 0, 0))
                entry.compress_type = zipfile.ZIP_DEFLATED
                archive.writestr(entry, data)
                manifest.append({"package": name, "path": relative,
                                 "sha256": hashlib.sha256(data).hexdigest()})
        print(name, hashlib.sha256(target.read_bytes()).hexdigest())
    (output / "source-manifest.json").write_text(json.dumps(manifest, indent=2))


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    prepare(Path(sys.argv[1]), Path(sys.argv[2]))
