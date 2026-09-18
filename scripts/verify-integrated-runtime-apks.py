#!/usr/bin/env python3
"""Verify final APKs against ADR-0049; never infer exclusion from UI flags."""
import os
import argparse
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SDK = Path(os.environ.get("ANDROID_HOME", str(Path.home() / "Library/Android/sdk")))
ANALYZER = SDK / "cmdline-tools/latest/bin/apkanalyzer"
A = "{http://schemas.android.com/apk/res/android}"
COMPONENTS = {
    "com.helix.runtime.cli.app.CliRuntimeService": ("service", ":subscriptions"),
    "com.helix.runtime.proot.app.ProotRuntimeService": ("service", ":proot"),
    "com.helix.runtime.proot.app.ProotDetachedJobService": ("service", ":proot"),
    "com.helix.runtime.proot.app.ProotJobStopReceiver": ("receiver", ":proot"),
}
for activity in ("CodexLoginActivity", "CopilotLoginActivity", "ClaudeLoginActivity",
                 "GrokLoginActivity", "CliRuntimeHomeActivity", "SubscriptionNetworkSettingsActivity"):
    COMPONENTS["com.helix.runtime.cli.app." + activity] = ("activity", ":subscriptions")
for activity in ("ProotRepairActivity", "ProotLegalActivity"):
    COMPONENTS["com.helix.runtime.proot.app." + activity] = ("activity", ":proot")


def verify(flavor, build_type):
    apks = list((ROOT / f"app/build/outputs/apk/{flavor}/{build_type}").glob(f"app-{flavor}-{build_type}*.apk"))
    assert len(apks) == 1, f"expected one {flavor} {build_type} APK: {apks}"
    apk = apks[0]
    manifest = ET.fromstring(subprocess.check_output([str(ANALYZER), "manifest", "print", str(apk)]))
    app = manifest.find("application")
    package = manifest.attrib["package"]
    developer = flavor == "developer"
    assert package == "com.helix.agent" + (".developer" if developer else "")
    components = {element.get(A + "name"): element for element in app}
    for name, (kind, suffix) in COMPONENTS.items():
        component = components.get(name)
        if not developer:
            assert component is None, f"consumer leaked {name}"
            continue
        assert component is not None and component.tag == kind, f"missing {name}"
        assert component.get(A + "exported") == "false", f"exported {name}"
        assert component.get(A + "process") in (suffix, package + suffix), f"wrong process {name}"
        assert component.get(A + "isolatedProcess", "false") == "false", f"unsupported isolated runtime {name}"
    quickjs = [element for element in app.findall("service") if element.get(A + "isolatedProcess") == "true"]
    assert quickjs and all(element.get(A + "exported") == "false" for element in quickjs)
    permissions = {element.get(A + "name") for element in manifest.findall("uses-permission")}
    assert ("android.permission.FOREGROUND_SERVICE_SPECIAL_USE" in permissions) == developer
    probe = "com.helix.app.proot.DetachedOwnerProbeActivity"
    assert (probe in components) == (developer and build_type == "debug"), "debug owner-death probe leaked"
    if developer:
        detached = components["com.helix.runtime.proot.app.ProotDetachedJobService"]
        # apkanalyzer decodes this framework flag numerically on some SDK versions.
        service_type = detached.get(A + "foregroundServiceType")
        assert service_type == "specialUse" or int(service_type, 0) == 0x40000000
        assert detached.find("property[@" + A + "name='android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE']") is not None
    assert "android.permission.REQUEST_INSTALL_PACKAGES" not in permissions
    launchers = app.findall(".//category[@" + A + "name='android.intent.category.LAUNCHER']")
    assert len(launchers) == 1, f"{flavor} has extra launcher"
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        assert not any(name.startswith("assets/companions/") or name.endswith(".apk") for name in names)
        for asset in ("assets/runtime/runtime-lock.json", "assets/cli/cli-runtime-lock.json"):
            assert (asset in names) == developer, f"wrong {flavor} asset {asset}"
        dex = b"".join(archive.read(name) for name in names if name.endswith(".dex"))
        assert (b"Lcom/helix/app/proot/DetachedOwnerProbeActivity;" in dex) == (developer and build_type == "debug")
        for namespace in (b"Lcom/helix/runtime/cli/", b"Lcom/helix/runtime/proot/"):
            assert (namespace in dex) == developer, f"wrong {flavor} dex {namespace}"
        native = [name for name in names if name.endswith("/libhelix_loader.so")]
        assert bool(native) == developer, f"wrong {flavor} PRoot native payload"
        if developer:
            assert app.get(A + "extractNativeLibs") == "true", "PRoot executable must be extracted at install"
            import hashlib
            import json
            lock = json.loads(archive.read("assets/runtime/runtime-lock.json"))
            rootfs = next(component for component in lock["components"] if component["id"] == "alpine-rootfs")
            payload = "assets/runtime/rootfs/" + rootfs["url"].rsplit("/", 1)[1].removesuffix(".gz")
            assert hashlib.sha256(archive.read(payload)).hexdigest() == rootfs["sha256"], "RootFS missing or mismatched"
            for asset in ("proot", "loader", "lib/libtalloc.so.2", "lib/libandroid-shmem.so"):
                assert "assets/runtime/proot/" + asset in names, f"missing runtime input {asset}"
    print(f"{flavor} {build_type}: APK components, process/UID contract, payloads and launcher verified")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build-type", choices=("debug", "release"), default="debug")
    args = parser.parse_args()
    verify("consumer", args.build_type)
    verify("developer", args.build_type)
