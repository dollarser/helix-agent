"""Preserve ignored evidence and runtime inputs before an authorized worktree retirement."""
from pathlib import Path
import hashlib
import json
import shutil
import subprocess

root = Path(__file__).resolve().parents[3]
source = root / ".claude/worktrees/harness-2.0"
revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=source, text=True).strip()
assert revision.startswith("69182f53"), "Recheck source changes before retirement"
assert not subprocess.check_output(["git", "status", "--porcelain"], cwd=source)
archive = root / "build/worktree-archives" / ("harness-2.0-" + revision[:8])
archive.mkdir(parents=True, exist_ok=False)
manifest = []


def digest(path):
    hasher = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            hasher.update(chunk)
    return hasher.hexdigest()


def preserve(path, destination):
    if path.is_symlink():
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.symlink_to(path.readlink())
        assert destination.readlink() == path.readlink()
        manifest.append({"source": str(path.relative_to(source)),
                         "destination": str(destination.relative_to(root)),
                         "link": str(path.readlink())})
    elif path.is_dir():
        destination.mkdir(parents=True, exist_ok=True)
        for child in path.iterdir():
            preserve(child, destination / child.name)
    elif path.is_file():
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, destination)
        sha = digest(path)
        assert digest(destination) == sha, str(path.relative_to(source))
        manifest.append({"source": str(path.relative_to(source)),
                         "destination": str(destination.relative_to(root)),
                         "bytes": path.stat().st_size, "sha256": sha})
    else:
        raise RuntimeError(f"Unsupported local evidence entry: {path.relative_to(source)}")


preserve(source / "build", archive / "build")
# Keep XMLs, reports, APKs and other outputs; compiled intermediates are rebuildable.
for build in sorted(source.glob("**/build")):
    relative = build.relative_to(source)
    if relative.parts[0] in ("build", ".gradle", ".git"):
        continue
    for name in ("reports", "test-results", "outputs"):
        path = build / name
        if path.exists():
            preserve(path, archive / relative / name)
if (source / "local.properties").exists():
    preserve(source / "local.properties", archive / "local.properties")

for name in ("proot", "rootfs"):
    relative = Path("runtime/proot-app/src/main/assets/runtime") / name
    preserve(source / relative, archive / relative)
    destination = root / relative
    if destination.exists():
        # Never overwrite prior main assets without retaining their original bytes.
        shutil.copytree(destination, archive / "previous-main" / relative, symlinks=True)
    shutil.copytree(source / relative, destination, dirs_exist_ok=True, symlinks=True)
    for path in (source / relative).rglob("*"):
        if path.is_file() and not path.is_symlink():
            assert digest(path) == digest(root / path.relative_to(source))

(archive / "manifest.json").write_text(json.dumps({
    "source_commit": revision, "entries": manifest,
    "omitted": ["Gradle caches", "native build intermediates", "Python bytecode caches"],
}, indent=2) + "\n")
print(json.dumps({"archive": str(archive.relative_to(root)), "entries": len(manifest),
                  "bytes": sum(row.get("bytes", 0) for row in manifest)}))
