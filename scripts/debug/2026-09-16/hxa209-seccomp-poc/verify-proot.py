#!/usr/bin/env python3
"""Owned-emulator PRoot control + guarded checks; no borrowed devices."""
import os, pathlib, shlex, subprocess, time, tarfile, zipfile, json
ROOT = pathlib.Path(__file__).resolve().parents[4]
SDK = pathlib.Path(os.environ.get('ANDROID_HOME', str(pathlib.Path.home() / 'Library/Android/sdk')))
OUT = ROOT / 'build/hxa209-network-verification'
OUT.mkdir(parents=True, exist_ok=True)
serial = 'emulator-5582'
adb = [str(SDK / 'platform-tools/adb'), '-s', serial]
def run(args, **kw):
    r = subprocess.run(args, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=kw.pop('timeout', 120), **kw)
    print(r.stdout, end='', flush=True)
    r.check_returncode()
    return r.stdout
def shell(args):
    return run(adb + ['shell', shlex.join(args)])
if serial in run([str(SDK / 'platform-tools/adb'), 'devices']):
    raise RuntimeError('device already owned')
apk = ROOT / 'app/build/outputs/apk/developer/debug/app-developer-debug.apk'
assets = OUT / 'assets'
with zipfile.ZipFile(apk) as z:
    for name in z.namelist():
        if name.startswith(('assets/runtime/proot/', 'assets/runtime/rootfs/')):
            z.extract(name, assets)
archive = next((assets / 'assets/runtime/rootfs').glob('*.tar'))
local = OUT / 'rootfs.tar'
with tarfile.open(archive) as src, tarfile.open(local, 'w', format=tarfile.GNU_FORMAT) as dest:
    for m in src:
        m.name = m.name.lstrip('/')
        m.uid = m.gid = 2000
        m.uname = m.gname = 'shell'
        if '..' in pathlib.PurePosixPath(m.name).parts:
            raise RuntimeError('unsafe archive member')
        dest.addfile(m, src.extractfile(m) if m.isfile() else None)
log = open(OUT / 'emulator.log', 'w')
p = subprocess.Popen([str(SDK / 'emulator/emulator'), '-avd', os.environ.get('POC_AVD', 'Helix_API_36'), '-port', '5582', '-read-only', '-no-snapshot', '-no-window', '-no-audio'], stdout=log, stderr=subprocess.STDOUT)
try:
    for _ in range(180):
        if p.poll() is not None: raise RuntimeError('emulator exited')
        r = subprocess.run(adb + ['shell', 'getprop', 'sys.boot_completed'], capture_output=True, text=True, timeout=10)
        if r.stdout.strip() == '1': break
        time.sleep(1)
    else: raise RuntimeError('boot timeout')
    if os.environ.get('POC_APP_PROBE') == '1':
        run(adb+['install','-r',str(apk)],timeout=180)
        run(adb+['install','-r',str(ROOT/'app/build/outputs/apk/androidTest/developer/debug/app-developer-debug-androidTest.apk')],timeout=180)
        package='com.helix.agent.developer'
        shell(['run-as',package,'mkdir','-p','files/hxa209-network-probe'])
        for name in ('guard','diag','filterprobe','fdprobe','delegation-probe'):
            with open(ROOT/'build/hxa209-poc'/name,'rb') as payload:
                subprocess.run(adb+['shell','-T',shlex.join(['run-as',package,'sh','-c','cat > files/hxa209-network-probe/'+name])],stdin=payload,check=True,timeout=30)
        result=shell(['am','instrument','-w','-r','-e','hxa209NetworkProbe','true','-e','class','com.helix.app.proot.ProotNetworkProbeDeviceTest',package+'.test/com.helix.app.HelixAndroidJUnitRunner'])
        evidence=shell(['run-as',package,'cat','files/hxa209-network-probe/observations.txt'])
        (OUT/('app-observations-'+os.environ.get('POC_AVD','Helix_API_36')+'.txt')).write_text(evidence)
        assert 'OK (1 test)' in result, result
    base = '/data/local/tmp/helix-network-review'
    shell(['mkdir', '-p', base+'/lib', base+'/tmp', base+'/ws', base+'/rootfs'])
    proot = assets / 'assets/runtime/proot'
    files = { 'proot':proot/'proot', 'loader':proot/'loader', 'lib/libtalloc.so.2':proot/'lib/libtalloc.so.2', 'lib/libandroid-shmem.so':proot/'lib/libandroid-shmem.so', 'rootfs.tar':local }
    for name in ('guard','netprobe','filterprobe','diag','fdprobe','delegation-probe'):
        files['tmp/'+name] = ROOT/'build/hxa209-poc'/name
    for target, source in files.items(): run(adb+['push',str(source),base+'/'+target])
    shell(['sh','-c',f'chmod 700 {base}/tmp/* {base}/proot {base}/loader; cd {base}/rootfs; tar -xf ../rootfs.tar'])
    env = ['env','LD_LIBRARY_PATH='+base+'/lib','PROOT_LOADER='+base+'/loader','PROOT_TMP_DIR='+base+'/tmp','PATH=/usr/bin:/bin']
    command = ['/system/bin/linker64',base+'/proot','-r',base+'/rootfs','-b','/dev','-b','/proc','-b',base+'/tmp:/tmp','-b',base+'/ws:/workspace','-w','/workspace']
    script = 'echo file-ok > /workspace/check; cat /workspace/check; echo pipe-ok | cat; /tmp/netprobe; /bin/sh -c /tmp/netprobe'
    print('BASELINE PROOT', flush=True)
    baseline = shell(env+command+['/bin/sh','-c',script])
    assert 'file-ok' in baseline and 'udp4  connect: OK' in baseline and 'inet6 socket: OK' in baseline
    print('GUARDED PROOT', flush=True)
    def regular_io(args):
        shell(['sh', '-c', shlex.join(args)+' < '+base+'/ws/check > '+base+'/capture 2>&1'])
        return shell(['cat',base+'/capture'])
    guarded = regular_io(env+[base+'/tmp/guard']+command+['/bin/sh','-c',script])
    assert 'file-ok' in guarded and 'pipe-ok' in guarded
    for item in ('udp4  socket: errno=1 ', 'tcp4  socket: errno=1 ', 'inet6 socket: errno=1 '):
        assert guarded.count(item) == 2, (item, guarded)
    print('INHERITED SOCKET REGRESSION', flush=True)
    regular_io([base+'/tmp/fdprobe',base+'/tmp/guard'])
    regular_io([base+'/tmp/delegation-probe',base+'/tmp/guard'])
    print('PRoot baseline/guard/descendant checks PASS; inherited-FD regression PASS', flush=True)
finally:
    p.terminate()
    try: p.wait(timeout=30)
    except subprocess.TimeoutExpired:
        p.kill(); p.wait(timeout=10)
    log.close()
