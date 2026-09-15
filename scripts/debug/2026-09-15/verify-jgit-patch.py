"""Verify the local JGit transform's entry-level scope, upstream license and bytecode."""
from pathlib import Path
import hashlib
import json
import os
import subprocess
import zipfile

root = Path(__file__).resolve().parents[3]
cache = Path(os.environ.get('GRADLE_USER_HOME', str(Path.home() / '.gradle')))
originals = list((cache/'caches/modules-2/files-2.1/org.eclipse.jgit/org.eclipse.jgit/6.10.0.202406032230-r').glob('*/*.jar'))
assert len(originals) == 1
original = originals[0]
patched = sorted((cache/'caches/9.5.0/transforms').glob('*/transformed/*-helix-tls.jar'))
assert patched
out = root/'build/hxa200-jgit-patch'
out.mkdir(parents=True, exist_ok=True)
reports = []
for artifact in patched:
    with zipfile.ZipFile(original) as before, zipfile.ZipFile(artifact) as after:
        removed = sorted(set(before.namelist()) - set(after.namelist()))
        added = sorted(set(after.namelist()) - set(before.namelist()))
        changed = sorted(n for n in after.namelist() if n in before.namelist() and before.read(n) != after.read(n))
        assert not added
        assert changed == ['org/eclipse/jgit/transport/http/NoCheckX509TrustManager.class']
        assert all(n.startswith('META-INF/') and n.rsplit('.', 1)[-1] in ['SF','RSA','DSA','EC'] for n in removed)
        licenses = [n for n in before.namelist() if 'license' in n.lower() or n.endswith('about.html')]
        assert licenses
        for n in licenses:
            assert before.read(n) == after.read(n)
            (out / Path(n).name).write_bytes(before.read(n))
    result = subprocess.run([str(Path(os.environ['JAVA_HOME'])/'bin/javap'), '-classpath', str(artifact), '-c',
                             'org.eclipse.jgit.transport.http.NoCheckX509TrustManager'], check=True, text=True, capture_output=True)
    assert result.stdout.count('athrow') == 2
    (out/'patched-bytecode.txt').write_text(result.stdout)
    reports.append(dict(sha256=hashlib.sha256(artifact.read_bytes()).hexdigest(), changed=changed, removed=removed, licenses=licenses))
# Transform classes compiled from different build-script revisions must still emit identical bytes.
assert len({row['sha256'] for row in reports}) == 1
(out/'summary.json').write_text(json.dumps(reports, indent=2))
print(json.dumps(reports[-1]))
