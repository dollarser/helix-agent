#!/usr/bin/env python3
"""Read-only HXA-197 candidate audit. Downloads stay in ignored build/, never added as dependencies."""
import hashlib
import json
from pathlib import Path
import subprocess
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / "build/hxa197-termlib-0.2.1"
BASE = "https://repo.maven.apache.org/maven2/org/connectbot/termlib"
VERSION = "0.2.1"
OUT.mkdir(parents=True, exist_ok=True)
hashes = {}
for name, url in [("maven-metadata.xml", f"{BASE}/maven-metadata.xml")] + [
    (f"termlib-{VERSION}{suffix}", f"{BASE}/{VERSION}/termlib-{VERSION}{suffix}")
    for suffix in (".pom", ".module", ".aar", "-sources.jar")
]:
    with urllib.request.urlopen(url, timeout=60) as response:
        data = response.read()
    (OUT / name).write_bytes(data)
    hashes[name] = dict(url=url, sha256=hashlib.sha256(data).hexdigest(), bytes=len(data))

native = []
readelf = Path.home() / "Library/Android/sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-readelf"
with zipfile.ZipFile(OUT / f"termlib-{VERSION}.aar") as archive:
    (OUT / "aar-entries.txt").write_text("\n".join(archive.namelist()) + "\n")
    for name in archive.namelist():
        if name.endswith(".so"):
            target = OUT / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(archive.read(name))
            report = subprocess.check_output([str(readelf), "-l", "-d", str(target)], text=True)
            target.with_suffix(".elf.txt").write_text(report)
            native.append(dict(path=name, elf_report=str(target.with_suffix(".elf.txt").relative_to(OUT))))
    for name in ("AndroidManifest.xml", "META-INF/com/android/build/gradle/aar-metadata.properties"):
        if name in archive.namelist():
            (OUT / Path(name).name).write_bytes(archive.read(name))
with zipfile.ZipFile(OUT / f"termlib-{VERSION}-sources.jar") as archive:
    (OUT / "source-entries.txt").write_text("\n".join(archive.namelist()) + "\n")
(OUT / "inspection.json").write_text(json.dumps(dict(artifacts=hashes, native=native), indent=2) + "\n")
print(json.dumps(dict(version=VERSION, native=native, artifacts=hashes), indent=2))
