"""Apply reviewed, hash-pinned FFmpeg corrections without modifying upstream source evidence."""
from pathlib import Path
import shutil
import subprocess
import tempfile

from kit import HERE, OUT, RECIPE, atomic_json, digest, identity, proof_matches, require, tree_manifest


def apply_patch(source: Path, patch: Path, record: dict) -> None:
    target = record['target']
    require(target == 'libavcodec/mediacodecenc.c', 'Unexpected patch target')
    content = patch.read_text()
    require([line for line in content.splitlines() if line.startswith(('--- ', '+++ '))] ==
            ['--- a/' + target, '+++ b/' + target], 'Patch changes an unapproved path')
    require(digest(patch) == record['sha256'], 'Source patch SHA mismatch')
    file = source / target
    require(file.is_file() and not file.is_symlink(), 'Invalid source patch target')
    require(digest(file) == record['before_sha256'], 'Source patch belongs to another upstream file')
    subprocess.run(['patch', '-p1', '-F0', '--batch', '-i', str(patch.resolve())],
                   cwd=source, check=True, capture_output=True, text=True, timeout=30)
    require(digest(file) == record['after_sha256'], 'Source patch output SHA mismatch')


def patched_source(upstream: Path) -> Path:
    records = RECIPE['patches']
    require(bool(records), 'Missing reviewed FFmpeg source patches')
    for name, record in records.items():
        require(Path(name).name == name and name.endswith('.patch'), 'Invalid source patch name')
        require(digest(HERE / name) == record['sha256'], 'Source patch SHA mismatch')
    key = identity({'upstream': RECIPE['sources']['ffmpeg']['sha256'], 'patches': records})
    destination = OUT / 'source-patched' / ('ffmpeg-' + key)
    proof = OUT / 'source-proof' / ('patched-ffmpeg-' + key + '.json')
    if destination.exists():
        require(proof_matches(proof, key, destination), 'Patched source was modified; do not compile')
        return destination
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='ffmpeg-patch-', dir=destination.parent) as temp:
        staging = Path(temp) / 'source'
        shutil.copytree(upstream, staging, symlinks=True)
        for name, record in records.items():
            apply_patch(staging, HERE / name, record)
        staging.rename(destination)
    atomic_json(proof, {'key': key, 'patches': records, 'files': tree_manifest(destination)})
    return destination
