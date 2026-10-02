#!/usr/bin/env python3
"""Verify final APKs against ADR-0049; never infer exclusion from UI flags."""
import os
import argparse
import re
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
    "com.helix.runtime.proot.app.ProotTerminalService": ("service", ":proot"),
    "com.helix.runtime.proot.app.ProotJobStopReceiver": ("receiver", ":proot"),
}
for activity in ("CodexLoginActivity", "CopilotLoginActivity", "ClaudeLoginActivity", "AntigravityLoginActivity",
                 "GrokLoginActivity", "CliRuntimeHomeActivity", "SubscriptionNetworkSettingsActivity"):
    COMPONENTS["com.helix.runtime.cli.app." + activity] = ("activity", ":subscriptions")
for activity in ("ProotRepairActivity", "ProotLegalActivity"):
    COMPONENTS["com.helix.runtime.proot.app." + activity] = ("activity", ":proot")


def verify_http_network_config(app, config, resource_table=""):
    """HXA-242: resolve the actual compiled reference, not an arbitrary @ref value."""
    reference = app.get(A + "networkSecurityConfig")
    if reference != "@xml/network_security_config":
        matches = re.findall(r"^\s*resource (0x[0-9a-fA-F]+) xml/network_security_config\s*$",
                             resource_table, re.MULTILINE)
        expected = "@ref/" + matches[0] if len(matches) == 1 else None
        if expected is None or reference != expected:
            raise RuntimeError("Missing shared HTTP network configuration in final APK")
    if config.tag != "network-security-config" or len(config) != 1:
        raise RuntimeError("Unexpected network configuration overrides")
    base = config.find("base-config")
    if base is None or base.get("cleartextTrafficPermitted") != "true" or len(base):
        raise RuntimeError("HTTP unavailable or default TLS trust modified")


def verify_mobile_use(app, developer, config=None, resource_table=""):
    """HXA-243: inspect actual service metadata, not just a dependency or UI flag."""
    name = "com.helix.tools.automation.HelixAccessibilityService"
    services = [item for item in app.findall("service") if item.get(A + "name") == name]
    if not developer:
        if services or config is not None:
            raise RuntimeError("Standard APK leaked Mobile Use service/capabilities")
        return
    if len(services) != 1 or config is None or config.tag != "accessibility-service":
        raise RuntimeError("Missing Mobile Use service/capabilities")
    service = services[0]
    if service.get(A + "permission") != "android.permission.BIND_ACCESSIBILITY_SERVICE":
        raise RuntimeError("Accessibility service is not system-permission protected")
    if service.get(A + "exported") != "true":
        raise RuntimeError("System cannot bind Accessibility service")
    entries = [item for item in service.findall("meta-data")
               if item.get(A + "name") == "android.accessibilityservice"]
    reference = entries[0].get(A + "resource") if len(entries) == 1 else None
    if reference != "@xml/helix_accessibility_service":
        matches = re.findall(r"^\s*resource (0x[0-9a-fA-F]+) xml/helix_accessibility_service\s*$",
                             resource_table, re.MULTILINE)
        expected = "@ref/" + matches[0] if len(matches) == 1 else None
        if expected is None or reference != expected:
            raise RuntimeError("Accessibility metadata does not reference the inspected configuration")
    for field in ("canRetrieveWindowContent", "canPerformGestures", "canTakeScreenshot"):
        if config.get(A + field) != "true":
            raise RuntimeError("Missing declared Mobile Use capability: " + field)


def verify_media_payload(archive, developer):
    import hashlib
    import json
    names = archive.namelist()
    native_names = {'libavcodec.so', 'libavformat.so', 'libavfilter.so', 'libavutil.so',
                    'libswscale.so', 'libswresample.so', 'libhelix_ffmpeg.so', 'libhelix_ffprobe.so'}
    present = {name for name in names if name.rsplit('/', 1)[-1] in native_names}
    assets = {name for name in names if name.startswith('assets/runtime/media/')}
    if not developer:
        if present or assets:
            raise RuntimeError('Standard APK contains Advanced FFmpeg payload')
        return
    if present != {'lib/arm64-v8a/' + name for name in native_names}:
        raise RuntimeError('Missing, additional or duplicate-ABI FFmpeg payload')
    for name in ('candidate.json', 'NOTICE.txt', 'USAGE.md', 'sources.json', 'LICENSES/dav1d/COPYING'):
        if 'assets/runtime/media/' + name not in assets:
            raise RuntimeError('Missing FFmpeg evidence or legal material: ' + name)
    manifest = json.loads(archive.read('assets/runtime/media/candidate.json'))
    if manifest['profile'] != 'ready-av1' or manifest['target'] != 'arm64-v8a':
        raise RuntimeError('Wrong FFmpeg configuration')
    if manifest['artifact_identity'] != '424662b10eb22413c752a62bf3aabe9daa5e9544dc5ba76a021ca14e0c7236c7':
        raise RuntimeError('FFmpeg identity changed without an explicit verifier update')
    total = 0
    for row in manifest['files']:
        original = row['name']
        name = original if original.endswith('.so') else 'libhelix_' + original + '.so'
        content = archive.read('lib/arm64-v8a/' + name)
        if len(content) != row['bytes'] or hashlib.sha256(content).hexdigest() != row['sha256']:
            raise RuntimeError('APK altered pinned FFmpeg bytes: ' + name)
        total += len(content)
    if total != manifest['runtime_bytes'] or total >= 25_000_000:
        raise RuntimeError('FFmpeg runtime closure exceeds the agreed size boundary')


def verify(flavor, build_type):
    apks = list((ROOT / f"app/build/outputs/apk/{flavor}/{build_type}").glob(f"app-{flavor}-{build_type}*.apk"))
    assert len(apks) == 1, f"expected one {flavor} {build_type} APK: {apks}"
    apk = apks[0]
    manifest = ET.fromstring(subprocess.check_output([str(ANALYZER), "manifest", "print", str(apk)]))
    app = manifest.find("application")
    network_config = ET.fromstring(subprocess.check_output([
        str(ANALYZER), "resources", "xml", "--file", "res/xml/network_security_config.xml", str(apk),
    ]))
    tools = list(SDK.glob("build-tools/*/aapt2"))
    if not tools:
        raise RuntimeError("aapt2 is required to resolve the packaged network resource identity")
    aapt = max(tools, key=lambda path: tuple(int(n) for n in re.findall(r"\d+", path.parent.name)))
    resources = subprocess.check_output([str(aapt), "dump", "resources", str(apk)], text=True)
    verify_http_network_config(app, network_config, resources)
    package = manifest.attrib["package"]
    developer = flavor == "developer"
    mobile_config = None
    if developer:
        mobile_config = ET.fromstring(subprocess.check_output([
            str(ANALYZER), "resources", "xml", "--file", "res/xml/helix_accessibility_service.xml", str(apk),
        ]))
    verify_mobile_use(app, developer, mobile_config, resources)
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
    terminal = components.get("com.helix.app.terminal.ManualTerminalActivity")
    assert (terminal is not None) == developer
    if terminal is not None:
        assert terminal.get(A + "exported") == "false"
        assert terminal.get(A + "process") in (None, package), "terminal UI must stay in main process"
    quickjs = [element for element in app.findall("service") if element.get(A + "isolatedProcess") == "true"]
    assert quickjs and all(element.get(A + "exported") == "false" for element in quickjs)
    permissions = {element.get(A + "name") for element in manifest.findall("uses-permission")}
    assert ("android.permission.FOREGROUND_SERVICE_SPECIAL_USE" in permissions) == developer
    probe = "com.helix.app.proot.DetachedOwnerProbeActivity"
    assert (probe in components) == (developer and build_type == "debug"), "debug owner-death probe leaked"
    pty_probe = "com.helix.runtime.proot.app.PtyNativeProbeService"
    assert (pty_probe in components) == (developer and build_type == "debug"), "debug PTY probe leaked"
    if pty_probe in components:
        assert components[pty_probe].get(A + "exported") == "false"
        assert components[pty_probe].get(A + "process") in (":proot", package + ":proot")
    if developer:
        for service in ("ProotDetachedJobService", "ProotTerminalService"):
            owner = components["com.helix.runtime.proot.app." + service]
            # apkanalyzer decodes this framework flag numerically on some SDK versions.
            service_type = owner.get(A + "foregroundServiceType")
            assert service_type == "specialUse" or int(service_type, 0) == 0x40000000
            assert owner.find("property[@" + A + "name='android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE']") is not None
    assert "android.permission.REQUEST_INSTALL_PACKAGES" not in permissions
    launchers = app.findall(".//category[@" + A + "name='android.intent.category.LAUNCHER']")
    assert len(launchers) == 1, f"{flavor} has extra launcher"
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        if ("assets/plugins/mobile-use/plugin.json" in names) != developer:
            raise RuntimeError("Incorrect channel for Mobile Use plugin manifest")
        verify_media_payload(archive, developer)
        assert ("assets/licenses/dsh-plugin-subscriptions.txt" in names) == developer
        assert not any(name.startswith("assets/companions/") or name.endswith(".apk") for name in names)
        for asset in ("assets/runtime/runtime-lock.json", "assets/cli/cli-runtime-lock.json"):
            assert (asset in names) == developer, f"wrong {flavor} asset {asset}"
        dex = b"".join(archive.read(name) for name in names if name.endswith(".dex"))
        for retired in (b"Lcom/helix/provider/api/CleartextAuthorization;",
                        b"Lcom/helix/app/provider/CleartextBindingStore;"):
            if retired in dex:
                raise RuntimeError("Obsolete HTTP consent implementation remains in APK")
        if b'Lcom/helix/runtime/media/' in dex:
            raise RuntimeError('Retired independent media Runtime is still packaged')
        assert (b"Lcom/helix/app/proot/DetachedOwnerProbeActivity;" in dex) == (developer and build_type == "debug")
        assert (b"Lcom/helix/runtime/proot/app/PtyNativeProbeService;" in dex) == (developer and build_type == "debug")
        for namespace in (b"Lcom/helix/runtime/cli/", b"Lcom/helix/runtime/proot/"):
            assert (namespace in dex) == developer, f"wrong {flavor} dex {namespace}"
        assert (b"Lorg/connectbot/terminal/" in dex) == developer
        for asset in ("NOTICE.txt", "Apache-2.0.txt", "libvterm-MIT.txt"):
            assert ("assets/terminal-licenses/" + asset in names) == developer
        assert any(name.endswith("/libjni_cb_term.so") for name in names) == developer
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
    print(f"{flavor} {build_type}: Mobile Use service, scope channel and screenshot/gesture declarations verified")
    print(f"{flavor} {build_type}: APK components, process/UID contract, payloads, launcher and HTTP/TLS configuration verified")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build-type", choices=("debug", "release"), default="debug")
    args = parser.parse_args()
    verify("consumer", args.build_type)
    verify("developer", args.build_type)
