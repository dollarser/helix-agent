#!/usr/bin/env python3
"""Run the project's already cached/pinned ktlint on its new fixture to locate hidden format errors."""
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

cache = Path.home() / '.gradle/caches/modules-2/files-2.1'
ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
seen = set()
jars = []
def collect(group, artifact, version):
    key = (group, artifact, version)
    if key in seen:
        return
    seen.add(key)
    directory = cache / group / artifact / version
    jars.extend(sorted(directory.glob('*/*.jar')))
    poms = sorted(directory.glob('*/*.pom'))
    if not poms:
        return
    root = ET.parse(poms[0]).getroot()
    for item in root.findall('m:dependencies/m:dependency', ns):
        value = lambda tag: item.findtext('m:' + tag, namespaces=ns)
        if value('scope') not in (None, 'compile', 'runtime') or value('optional') == 'true':
            continue
        if value('version') and '${' not in value('version'):
            collect(value('groupId'), value('artifactId'), value('version'))
collect('com.pinterest.ktlint', 'ktlint-cli', '1.8.0')
jars.extend(sorted((cache / 'org.slf4j/slf4j-api').glob('2.*/*/*.jar'), reverse=True))
collect('ch.qos.logback', 'logback-core', '1.3.16')
assert jars, 'Pinned cached ktlint unavailable'
file = 'app/src/androidTest/kotlin/com/helix/app/chat/ToolVisionFlowDeviceTest.kt'
result = subprocess.run(['java', '-cp', ':'.join(map(str, jars)), 'com.pinterest.ktlint.Main', '--format', file], timeout=60)
raise SystemExit(result.returncode)
