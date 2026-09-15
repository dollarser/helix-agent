"""Check the upgraded transform's byte scope and original license retention."""
from pathlib import Path
import hashlib,json,os,zipfile,subprocess
root=Path(__file__).resolve().parents[3]
cache=Path(os.environ.get('GRADLE_USER_HOME',str(Path.home()/'.gradle')))
version='7.8.0.202609011348-r'
original=next((cache/'caches/modules-2/files-2.1/org.eclipse.jgit/org.eclipse.jgit'/version).glob('*/*.jar'))
assert hashlib.sha256(original.read_bytes()).hexdigest()=='cc63976f92e8058d05a543f320a6237accf2f17b745ab20946f246ac0b54dfd6'
artifacts=list((cache/'caches').glob('*/transforms/*/transformed/org.eclipse.jgit-'+version+'-helix-tls.jar'))
assert artifacts
artifact=max(artifacts,key=lambda p:p.stat().st_mtime_ns)
with zipfile.ZipFile(original) as before,zipfile.ZipFile(artifact) as after:
 added=sorted(set(after.namelist())-set(before.namelist()))
 removed=sorted(set(before.namelist())-set(after.namelist()))
 changed=sorted(n for n in before.namelist() if n in after.namelist() and before.read(n)!=after.read(n))
 assert added==['com/helix/jgit/AndroidInputStreams.class']
 assert all(n.startswith('META-INF/') and n.rsplit('.',1)[-1] in ['SF','RSA','DSA','EC'] for n in removed)
 assert all(n=='org/eclipse/jgit/transport/http/NoCheckX509TrustManager.class' or b'readNBytes' in before.read(n) or b'readAllBytes' in before.read(n) for n in changed)
 licenses=[n for n in before.namelist() if 'license' in n.lower() or n.endswith('about.html')]
 assert licenses and all(before.read(n)==after.read(n) for n in licenses)
result=subprocess.run([str(Path(os.environ['JAVA_HOME'])/'bin/javap'),'-classpath',str(artifact),'-c','org.eclipse.jgit.transport.http.NoCheckX509TrustManager'],check=True,capture_output=True,text=True)
assert result.stdout.count('athrow')==2
out=root/'build/pre-hxa-regression-20260916/jgit-artifact.json'
out.write_text(json.dumps(dict(upstreamSha=hashlib.sha256(original.read_bytes()).hexdigest(),patchedSha=hashlib.sha256(artifact.read_bytes()).hexdigest(),added=added,removed=removed,changed=changed,licenses=licenses),indent=2))
print(out.relative_to(root))
