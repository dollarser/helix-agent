#!/usr/bin/env python3
"""Temporarily stage debug-only fixtures; never add the probe to a normal build."""
import os, pathlib, shutil, subprocess
ROOT=pathlib.Path(__file__).resolve().parents[4]
SRC=pathlib.Path(__file__).resolve().parent
NDK=pathlib.Path(os.environ.get('NDK',str(pathlib.Path.home()/'Library/Android/sdk/ndk/28.2.13676358')))
OUT=ROOT/'build/isolated-proot-spike'
OUT.mkdir(parents=True,exist_ok=True)
raw=OUT/'libhelix_isolated_probe.so'
subprocess.run([str(NDK/'toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android29-clang'),'-Wall','-Wextra','-Werror',str(SRC/'native-probe.c'),'-o',str(raw)],check=True)
assets=ROOT/'runtime/proot-app/src/main/assets/runtime/proot'
proot=(assets/'proot').read_bytes()
assert b'libtalloc.so.2\0' in proot
# APK native extraction uses lib*.so names. Keep offsets/length unchanged.
# Only diagnostic bytes change; production assets and lock remain untouched.
proot=proot.replace(b'libtalloc.so.2\0',b'libtalloc.so\0\0\0')
files={
 'app/src/developerDebug/AndroidManifest.xml':(SRC/'fixtures/AndroidManifest.xml').read_bytes(),
 'app/src/developerDebug/kotlin/com/helix/app/proot/IsolatedProotFeasibilityService.kt':(SRC/'fixtures/main/IsolatedProotFeasibilityService.kt').read_bytes(),
 'app/src/androidTestDeveloper/kotlin/com/helix/app/proot/IsolatedProotFeasibilityDeviceTest.kt':(SRC/'fixtures/test/IsolatedProotFeasibilityDeviceTest.kt').read_bytes(),
 'app/src/developerDebug/jniLibs/arm64-v8a/libhelix_isolated_probe.so':raw.read_bytes(),
 'app/src/developerDebug/jniLibs/arm64-v8a/libhelix_proot_probe.so':proot,
 'app/src/developerDebug/jniLibs/arm64-v8a/libtalloc.so':(assets/'lib/libtalloc.so.2').read_bytes(),
 'app/src/developerDebug/jniLibs/arm64-v8a/libandroid-shmem.so':(assets/'lib/libandroid-shmem.so').read_bytes(),
}
for name in files:
    if (ROOT/name).exists(): raise RuntimeError('Refusing to overwrite '+name)
staged=[]
try:
    for name,contents in files.items():
        path=ROOT/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(contents);staged.append(path)
    subprocess.run(['./gradlew',':app:assembleDeveloperDebug',':app:assembleDeveloperDebugAndroidTest'],cwd=ROOT,check=True)
finally:
    for path in staged:
        if path.read_bytes()!=files[str(path.relative_to(ROOT))]: raise RuntimeError('Fixture changed concurrently; preserve '+str(path))
        path.unlink()
