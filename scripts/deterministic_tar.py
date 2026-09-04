#!/usr/bin/env python3
"""Deterministic repack of a POSIX/GNU tar stream (HXA-081 build pipeline).

The Docker-built rootfs tar is byte-for-byte non-reproducible (inode order,
mtimes, owner ids). The Runtime APK must embed a STABLE archive: its SHA-256
is recorded in runtime-lock.json (the 唯一版本真相) and re-verified by the
on-device installer (HXA-082). This tool repacks any tar into a canonical
form:

  * entries sorted by normalized absolute path (no leading "./", no duplicate "/"),
  * directories before files (implied by the sort for clean prefixes),
  * uid/gid forced to 0, uname/gname emptied (numeric ownership only),
  * mtime forced to a fixed constant (DEFAULT_MTIME),
  * modes and symlink targets preserved verbatim,
  * hard links materialized as regular file copies (the on-device extractor
    keeps a single code path: REG/DIR/SYM only),
  * GNU format (long names via the GNU mechanism, no PAX headers),
  * gzip level 9 with mtime=0.

Usage: deterministic_tar.py INPUT.tar OUTPUT.tar.gz
Exit 0 on success. The output is a function of the input's (path, mode,
type, target, content) tuples only — re-running on the same tree yields
identical bytes, so the lockfile hash stays valid across rebuilds.
"""

import gzip
import io
import sys
import tarfile

# Fixed mtime: 2026-01-01 00:00:00 UTC. Constant on purpose — the archive is
# content-addressed, not time-stamped.
DEFAULT_MTIME = 1767225600


def normalize(name: str) -> str:
    name = name.replace("\\", "/")
    while name.startswith("./"):
        name = name[2:]
    if not name.startswith("/"):
        name = "/" + name
    while "//" in name:
        name = name.replace("//", "/")
    return name.rstrip("/") or "/"


def main() -> int:
    if len(sys.argv) != 3:
        print(f"usage: {sys.argv[0]} INPUT.tar OUTPUT.tar.gz", file=sys.stderr)
        return 2

    src_path, dst_path = sys.argv[1], sys.argv[2]

    entries = []
    with tarfile.open(src_path, "r|*") as src:
        for member in src:
            path = normalize(member.name)
            if path == "/":
                continue  # the root directory is implicit
            if member.issym():
                entries.append(("sym", path, member.linkname, None))
            elif member.islnk():
                # Materialize hard links: read the target's content later.
                entries.append(("lnk", path, normalize(member.linkname), None))
            elif member.isdir():
                entries.append(("dir", path, "", None))
            elif member.isreg():
                f = src.extractfile(member)
                data = f.read() if f is not None else b""
                entries.append(("reg", path, member.mode, data))
            else:
                # Character/block/FIFO sockets must never enter a RootFS we
                # execute: fail closed rather than ship an exotic member.
                print(f"refusing non-regular tar member: {member.name} (type={member.type})",
                      file=sys.stderr)
                return 1

    entries.sort(key=lambda e: e[1])

    # Resolve hard-link targets from the regular-file set.
    contents = {path: data for kind, path, _, data in entries if kind == "reg"}
    for i, (kind, path, third, _) in enumerate(entries):
        if kind == "lnk":
            if third not in contents:
                print(f"hard link target missing from tar: {path} -> {third}", file=sys.stderr)
                return 1
            entries[i] = ("reg", path, 0o755, contents[third])

    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w", format=tarfile.GNU_FORMAT) as dst:
        for kind, path, third, data in entries:
            if kind == "dir":
                info = tarfile.TarInfo(path)
                info.type = tarfile.DIRTYPE
                info.mode = 0o755
            elif kind == "sym":
                info = tarfile.TarInfo(path)
                info.type = tarfile.SYMTYPE
                info.linkname = third
                info.mode = 0o777
            else:
                info = tarfile.TarInfo(path)
                info.type = tarfile.REGTYPE
                info.mode = third
                info.size = len(data)
            info.uid = 0
            info.gid = 0
            info.uname = ""
            info.gname = ""
            info.mtime = DEFAULT_MTIME
            dst.addfile(info, io.BytesIO(data) if kind == "reg" else None)

    with open(dst_path, "wb") as out:
        with gzip.GzipFile(filename="", mode="wb", fileobj=out, compresslevel=9, mtime=0) as gz:
            gz.write(buf.getvalue())
    return 0


if __name__ == "__main__":
    sys.exit(main())
