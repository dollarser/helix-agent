#!/usr/bin/env python3
"""Verify final APKs against ADR-0049; never infer exclusion from UI flags."""
import os
import argparse
import re
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET
import zipfile
import hashlib
import json
import lzma

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
                 "GrokLoginActivity", "CliRuntimeHomeActivity"):
    COMPONENTS["com.helix.runtime.cli.app." + activity] = ("activity", ":subscriptions")
for activity in ("ProotRepairActivity", "ProotLegalActivity"):
    COMPONENTS["com.helix.runtime.proot.app." + activity] = ("activity", ":proot")


def packaged_xml_path(resource_table, name):
    """Resolve the packaged path, including Release resource filename shortening."""
    blocks = re.split(r"(?m)^\s*resource ", resource_table)
    matches = [block.splitlines()[1:] for block in blocks if
               re.fullmatch(r"0x[0-9a-fA-F]+ xml/" + re.escape(name), block.splitlines()[0].strip() if block else "")]
    if len(matches) != 1:
        raise RuntimeError(f"Missing or ambiguous XML resource: {name}")
    entries = [line.strip() for line in matches[0] if line.strip()]
    path = re.fullmatch(r"\(\) \(file\) (res/[^\s]+\.xml) type=XML", entries[0]) if len(entries) == 1 else None
    if path is None:
        raise RuntimeError(f"Unexpected XML resource configurations: {name}")
    return path.group(1)


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


def verify_document_reader(app, package):
    """HXA-284: both channels parse untrusted PDF/DOCX only in a private isolated UID."""
    matches = [item for item in app.findall("service")
               if item.get(A + "name") == "com.helix.app.documents.DocumentReaderService"]
    if len(matches) != 1:
        raise RuntimeError("Missing or duplicate Document parser service")
    service = matches[0]
    if (service.get(A + "exported") != "false" or service.get(A + "isolatedProcess") != "true" or
            service.get(A + "process") not in (":document_reader", package + ":document_reader")):
        raise RuntimeError("Document parser must remain private, isolated and outside the main process")


def verify_mobile_app_visibility(manifest, developer):
    permissions = {item.get(A + "name") for item in manifest.findall("uses-permission")}
    if ("android.permission.QUERY_ALL_PACKAGES" in permissions) != developer:
        raise RuntimeError("App-picker package visibility must be present only in Advanced APK")


def verify_shizuku(app, developer):
    providers = [item for item in app.findall('provider') if item.get(A + 'name') == 'rikka.shizuku.ShizukuProvider']
    activities = [item for item in app.findall('activity') if item.get(A + 'name') == 'com.helix.tools.deviceaccess.ShizukuPermissionActivity']
    if not developer:
        if providers or activities:
            raise RuntimeError('Standard APK leaked Shizuku')
        return
    if len(providers) != 1 or len(activities) != 1:
        raise RuntimeError('Missing Shizuku provider or permission surface')
    provider = providers[0]
    if provider.get(A + 'permission') != 'android.permission.INTERACT_ACROSS_USERS_FULL' or provider.get(A + 'exported') != 'true':
        raise RuntimeError('Unprotected Shizuku provider')
    if activities[0].get(A + 'exported') != 'false':
        raise RuntimeError('Shizuku authorization must be user-operated and non-exported')


def verify_mobile_use(app, developer, config=None, resource_table=""):
    """HXA-243: inspect actual service metadata, not just a dependency or UI flag."""
    name = "com.helix.extensions.mobileuse.automation.HelixAccessibilityService"
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
    raw_flags = config.get(A + "accessibilityFlags") or ""
    required_flags = {
        "flagReportViewIds": 0x10,
        "flagRetrieveInteractiveWindows": 0x40,
        "flagIncludeNotImportantViews": 0x02,
    }
    if raw_flags.startswith("0x"):
        numeric_flags = int(raw_flags, 16)
        missing_flags = {name for name, bit in required_flags.items() if numeric_flags & bit == 0}
    else:
        symbolic_flags = set(raw_flags.split("|"))
        missing_flags = set(required_flags) - symbolic_flags
    if missing_flags:
        raise RuntimeError("Missing required Accessibility flags: " + ",".join(sorted(missing_flags)))


def verify_media_payload(archive, developer, abis=None):
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
    includes_arm64 = abis is None or 'arm64-v8a' in abis
    expected_native = {'lib/arm64-v8a/' + name for name in native_names} if includes_arm64 else set()
    if present != expected_native:
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
        if includes_arm64:
            content = archive.read('lib/arm64-v8a/' + name)
            if len(content) != row['bytes'] or hashlib.sha256(content).hexdigest() != row['sha256']:
                raise RuntimeError('APK altered pinned FFmpeg bytes: ' + name)
        total += row['bytes']
    if total != manifest['runtime_bytes'] or total >= 25_000_000:
        raise RuntimeError('FFmpeg runtime closure exceeds the agreed size boundary')


def original_dex_identities(classes, mapping):
    """Resolve retained class identities through R8; never keep code merely for a scanner."""
    identities = set(classes)
    for line in mapping.splitlines():
        match = re.fullmatch(r'([^ #\s]+) -> ([^\s]+):', line)
        if match:
            original, renamed = match.groups()
            if renamed in classes:
                identities.add(original)
    return b'\n'.join(('L' + name.replace('.', '/') + ';').encode() for name in sorted(identities))


def defined_dex_classes(apk):
    output = subprocess.check_output([str(ANALYZER), 'dex', 'packages', '--defined-only', str(apk)], text=True)
    classes = {fields[-1] for line in output.splitlines()
               if len(fields := line.split()) == 6 and fields[:2] == ['C', 'd']}
    if not classes:
        raise RuntimeError('APK analyzer returned no defined classes')
    return classes


def verify_rootfs_payload(archive):
    lock = json.loads(archive.read("assets/runtime/runtime-lock.json"))
    rootfs = next(component for component in lock["components"] if component["id"] == "alpine-rootfs")
    payload = "assets/runtime/rootfs/" + rootfs["url"].rsplit("/", 1)[1].removesuffix(".gz") + ".xz"
    if [n for n in archive.namelist() if n.startswith('assets/runtime/rootfs/')] != [payload]:
        raise RuntimeError('RootFS must contain exactly one encoded payload, without the raw tar')
    if not 0 < rootfs['size'] <= 256 * 1024 * 1024 or archive.getinfo(payload).file_size > 64 * 1024 * 1024:
        raise RuntimeError('RootFS payload exceeds runtime limits')
    decoder = lzma.LZMADecompressor(memlimit=16 * 1024 * 1024)
    digest = hashlib.sha256()
    count = 0
    with archive.open(payload) as source:
        while not decoder.eof:
            chunk = source.read(64 * 1024) if decoder.needs_input else b''
            if decoder.needs_input and not chunk:
                raise RuntimeError('Truncated RootFS XZ stream')
            decoded = decoder.decompress(chunk, max_length=64 * 1024)
            count += len(decoded)
            if count > rootfs['size']:
                raise RuntimeError('RootFS decoded bytes exceed lock')
            digest.update(decoded)
        if decoder.unused_data or source.read(1):
            raise RuntimeError('Trailing data after RootFS XZ stream')
    if count != rootfs['size'] or digest.hexdigest() != rootfs['sha256']:
        raise RuntimeError('RootFS decoded bytes differ from lock')


def verify(flavor, build_type, artifact_root=None, expected_abi=None):
    artifact_root = artifact_root or ROOT / "app/build/outputs"
    apks = list((artifact_root / f"apk/{flavor}/{build_type}").glob(f"app-{flavor}-{build_type}*.apk"))
    assert len(apks) == 1, f"expected one {flavor} {build_type} APK: {apks}"
    apk = apks[0]
    manifest = ET.fromstring(subprocess.check_output([str(ANALYZER), "manifest", "print", str(apk)]))
    app = manifest.find("application")
    tools = list(SDK.glob("build-tools/*/aapt2"))
    if not tools:
        raise RuntimeError("aapt2 is required to resolve the packaged network resource identity")
    aapt = max(tools, key=lambda path: tuple(int(n) for n in re.findall(r"\d+", path.parent.name)))
    resources = subprocess.check_output([str(aapt), "dump", "resources", str(apk)], text=True)
    network_config = ET.fromstring(subprocess.check_output([
        str(ANALYZER), "resources", "xml", "--file", packaged_xml_path(resources, "network_security_config"), str(apk),
    ]))
    verify_http_network_config(app, network_config, resources)
    package = manifest.attrib["package"]
    developer = flavor == "developer"
    verify_mobile_app_visibility(manifest, developer)
    mobile_config = None
    if developer:
        mobile_config = ET.fromstring(subprocess.check_output([
            str(ANALYZER), "resources", "xml", "--file", packaged_xml_path(resources, "helix_accessibility_service"), str(apk),
        ]))
    verify_mobile_use(app, developer, mobile_config, resources)
    verify_shizuku(app, developer)
    verify_document_reader(app, package)
    assert package == "com.helix.agent" + (".developer" if developer else "")
    components = {element.get(A + "name"): element for element in app}
    # Network configuration belongs to global settings; the retired subscription entry must not return.
    assert "com.helix.runtime.cli.app.SubscriptionNetworkSettingsActivity" not in components
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
        abis = {name.split('/')[1] for name in names if name.startswith('lib/') and name.endswith('.so')}
        expected_abis = {expected_abi} if expected_abi else {'arm64-v8a', 'x86_64'}
        if abis != expected_abis:
            raise RuntimeError(f'Unexpected native ABIs: {abis}, expected {expected_abis}')
        for abi in abis:
            for library in ('libhelix_model.so', 'libquickjs.so', 'libworkspace_identity.so'):
                if f'lib/{abi}/{library}' not in names:
                    raise RuntimeError(f'Missing required native library: {abi}/{library}')
        if ("assets/plugins/mobile-use/plugin.json" in names) != developer:
            raise RuntimeError("Incorrect channel for Mobile Use plugin manifest")
        verify_media_payload(archive, developer, abis)
        for license_file in ('Shizuku-API-MIT.txt', 'Shizuku-API-NOTICE.txt'):
            assert ('assets/licenses/' + license_file in names) == developer
        assert ("assets/licenses/dsh-plugin-subscriptions.txt" in names) == developer
        assert not any(name.startswith("assets/companions/") or name.endswith(".apk") for name in names)
        for asset in ("assets/runtime/runtime-lock.json", "assets/cli/cli-runtime-lock.json"):
            assert (asset in names) == developer, f"wrong {flavor} asset {asset}"
        dex = b"".join(archive.read(name) for name in names if name.endswith(".dex"))
        if build_type == 'release':
            mapping = artifact_root / f'mapping/{flavor}Release/mapping.txt'
            if not mapping.is_file() or not mapping.stat().st_size:
                raise RuntimeError('Optimized Release requires its own R8 mapping')
            dex = original_dex_identities(defined_dex_classes(apk), mapping.read_text())
        for namespace in (b"Lrikka/shizuku/", b"Lcom/helix/extensions/mobileuse/automation/backend/"):
            assert (namespace in dex) == developer, f"wrong {flavor} Shizuku dex {namespace}"
        for namespace in (b"Lcom/topjohnwu/superuser/", b"Lcom/helix/extensions/mobileuse/automation/backend/RootAutomationService;"):
            assert (namespace in dex) == developer, f"wrong {flavor} Root dex {namespace}"
        assert "com.helix.extensions.mobileuse.automation.backend.RootAutomationService" not in components, "libsu service is not an Android component"
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
        assert bool(native) == (developer and 'arm64-v8a' in abis), f"wrong {flavor} PRoot native payload"
        if developer:
            assert app.get(A + "extractNativeLibs") == "true", "PRoot executable must be extracted at install"
            verify_rootfs_payload(archive)
            for asset in ("proot", "loader", "lib/libtalloc.so.2", "lib/libandroid-shmem.so"):
                assert "assets/runtime/proot/" + asset in names, f"missing runtime input {asset}"
    print(f"{flavor} {build_type}: Mobile Use service, scope channel and screenshot/gesture declarations verified")
    print(f"{flavor} {build_type}: APK components, process/UID contract, payloads, launcher and HTTP/TLS configuration verified")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build-type", choices=("debug", "release"), default="debug")
    parser.add_argument("--artifact-root", type=Path, default=ROOT / "app/build/outputs")
    parser.add_argument("--expected-abi", choices=("arm64-v8a", "x86_64"))
    args = parser.parse_args()
    verify("consumer", args.build_type, args.artifact_root, args.expected_abi)
    verify("developer", args.build_type, args.artifact_root, args.expected_abi)
