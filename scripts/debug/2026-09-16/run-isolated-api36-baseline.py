"""Create and remove only this run's AVD; never reuse another running AVD."""
import os,subprocess,sys,uuid
from pathlib import Path
root=Path(__file__).resolve().parents[3]
manager=str(Path(os.environ['ANDROID_HOME'])/'cmdline-tools/latest/bin/avdmanager')
name='Helix_baseline_'+uuid.uuid4().hex[:10]
subprocess.run([manager,'create','avd','--name',name,'--package','system-images;android-36;google_apis;arm64-v8a','--device','pixel_5'],input='no\n',text=True,check=True)
try:
    subprocess.run([sys.executable,str(root/'scripts/debug/2026-09-16/run-pre-hxa-regressions.py'),'--api36','--avd',name,'--full',*sys.argv[1:]],cwd=root,check=True)
finally:
    subprocess.run([manager,'delete','avd','--name',name],check=True)
