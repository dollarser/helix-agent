#!/usr/bin/env python3
"""Reproducible experimental FFmpeg profiles, separate from both Helix production and the Lite baseline."""
from __future__ import annotations
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import time

ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / 'build/ffmpeg-plus-size-2026-10-01'
BASE = ROOT / 'build/ffmpeg-lite-size-2026-10-01'
SOURCE = BASE / 'source/ffmpeg-9.0.2'
SDK = Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Library/Android/sdk')))
NDK = SDK / 'ndk/28.2.13676358'
HOST_TAG = 'darwin-x86_64' if platform.system() == 'Darwin' else 'linux-x86_64'
TOOLS = NDK / 'toolchains/llvm/prebuilt' / HOST_TAG / 'bin'
VENV = OUT / 'tools-venv/bin'
COMMON = {
    'demuxer': 'concat,srt,ass,webvtt',
    'muxer': 'segment,stream_segment,srt,ass,webvtt,opus,webp,pcm_s16le',
    'decoder': 'subrip,ass,movtext,webvtt',
    'encoder': 'subrip,ass,movtext,webvtt',
    'filter': 'fade,xfade,acrossfade,silencedetect,silenceremove,loudnorm,dynaudnorm,equalizer,acompressor,alimiter',
}
EXTERNAL = {'encoder': 'libmp3lame,libopus,libwebp,libwebp_anim', 'filter': 'drawtext,subtitles,ass'}
CFLAGS = '-Os -fPIC -ffunction-sections -fdata-sections -fstack-protector-strong'


def run(argv, *, cwd=None, env=None, log, timeout=1200):
    path = OUT / 'logs' / log
    path.parent.mkdir(parents=True, exist_ok=True)
    print(json.dumps({'phase': log, 'started': True}), flush=True)
    with path.open('w') as output:
        result = subprocess.run([str(x) for x in argv], cwd=cwd or ROOT, env=env,
                                stdout=output, stderr=subprocess.STDOUT, timeout=timeout)
    if result.returncode:
        print(path.read_text(errors='replace')[-9000:], flush=True)
        raise RuntimeError(f'{log} exited {result.returncode}')
    print(json.dumps({'phase': log, 'passed': True}), flush=True)


def env_for(target):
    prefix = OUT / 'deps' / target
    env = dict(os.environ)
    env['PATH'] = str(VENV) + os.pathsep + env['PATH']
    env.update(LC_ALL='C', SOURCE_DATE_EPOCH='1790812800', ZERO_AR_DATE='1',
               PKG_CONFIG_LIBDIR=str(prefix / 'lib/pkgconfig'), PKG_CONFIG_PATH='',
               CFLAGS=CFLAGS, CXXFLAGS=CFLAGS + ' -fno-exceptions -fno-rtti',
               CPPFLAGS=f'-I{prefix}/include', LDFLAGS=f'-L{prefix}/lib')
    if target == 'arm64-v8a':
        env.update(CC=str(TOOLS / 'aarch64-linux-android29-clang'),
                   CXX=str(TOOLS / 'aarch64-linux-android29-clang++'),
                   AR=str(TOOLS / 'llvm-ar'), RANLIB=str(TOOLS / 'llvm-ranlib'),
                   STRIP=str(TOOLS / 'llvm-strip'), NM=str(TOOLS / 'llvm-nm'))
        env['LDFLAGS'] += ' -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384'
    else:
        env.update(CC='clang', CXX='clang++', AR='ar', RANLIB='ranlib', STRIP='strip', NM='nm')
    return prefix, env


def cmake_lib(name, target, opts, jobs):
    prefix, env = env_for(target)
    source = OUT / 'source' / name
    obj = OUT / 'deps-obj' / target / name
    args = ['cmake', '-S', source, '-B', obj, '-G', 'Unix Makefiles',
            '-DCMAKE_POLICY_VERSION_MINIMUM=3.5', '-DCMAKE_BUILD_TYPE=MinSizeRel',
            f'-DCMAKE_INSTALL_PREFIX={prefix}', '-DCMAKE_INSTALL_LIBDIR=lib',
            f'-DCMAKE_PREFIX_PATH={prefix}', '-DCMAKE_POSITION_INDEPENDENT_CODE=ON',
            '-DBUILD_SHARED_LIBS=OFF', '-DBUILD_TESTING=OFF',
            f'-DCMAKE_C_FLAGS={CFLAGS}', f'-DCMAKE_CXX_FLAGS={CFLAGS} -fno-exceptions -fno-rtti']
    if target == 'arm64-v8a':
        args += [f'-DCMAKE_TOOLCHAIN_FILE={NDK}/build/cmake/android.toolchain.cmake',
                 '-DANDROID_ABI=arm64-v8a', '-DANDROID_PLATFORM=android-29', '-DANDROID_STL=c++_shared']
    run(args + opts, env=env, log=f'{target}-{name}-configure.log')
    run(['cmake', '--build', obj, '--parallel', str(jobs)], env=env, log=f'{target}-{name}-build.log')
    run(['cmake', '--install', obj], env=env, log=f'{target}-{name}-install.log')


def autoconf_lib(name, target, opts, jobs):
    prefix, env = env_for(target)
    source = OUT / 'source' / name
    obj = OUT / 'deps-obj' / target / name
    obj.mkdir(parents=True, exist_ok=True)
    args = [source / 'configure', f'--prefix={prefix}', '--disable-shared', '--enable-static', '--with-pic']
    if target == 'arm64-v8a':
        args += ['--host=aarch64-linux-android', '--build=aarch64-apple-darwin']
    run(args + opts, cwd=obj, env=env, log=f'{target}-{name}-configure.log')
    run(['make', f'-j{jobs}'], cwd=obj, env=env, log=f'{target}-{name}-build.log')
    run(['make', 'install'], cwd=obj, env=env, log=f'{target}-{name}-install.log')


def meson_lib(name, target, opts, jobs):
    prefix, env = env_for(target)
    source = OUT / 'source' / name
    obj = OUT / 'deps-obj' / target / name
    obj.parent.mkdir(parents=True, exist_ok=True)
    args = [VENV / 'meson', 'setup', obj, source, '--prefix', prefix, '--libdir=lib',
            '--default-library=static', '--buildtype=minsize', '-Db_staticpic=true', '--wrap-mode=nofallback']
    if target == 'arm64-v8a':
        cross = OUT / 'deps-obj' / target / 'android.ini'
        content = "[binaries]\n" + ''.join(f"{key} = '{env[value]}'\n" for key, value in
                    [('c', 'CC'), ('cpp', 'CXX'), ('ar', 'AR'), ('strip', 'STRIP')])
        content += f"pkg-config = '{shutil.which('pkg-config')}'\n"
        content += "[host_machine]\nsystem='android'\ncpu_family='aarch64'\ncpu='aarch64'\nendian='little'\n"
        content += f"[built-in options]\nc_args=['-Os','-fPIC']\ncpp_args=['-Os','-fPIC','-fno-exceptions','-fno-rtti']\n[properties]\npkg_config_libdir='{prefix}/lib/pkgconfig'\n"
        cross.write_text(content)
        args += ['--cross-file', cross]
    if (obj / 'meson-private/coredata.dat').exists():
        args += ['--reconfigure']
    run(args + opts, env=env, log=f'{target}-{name}-configure.log')
    run([VENV / 'meson', 'compile', '-C', obj, '-j', str(jobs)], env=env, log=f'{target}-{name}-build.log')
    run([VENV / 'meson', 'install', '-C', obj], env=env, log=f'{target}-{name}-install.log')


def dependencies(target, jobs):
    builders = [
        ('lame', autoconf_lib, ['--disable-frontend', '--disable-decoder', '--disable-analyzer-hooks']),
        ('opus', cmake_lib, ['-DOPUS_BUILD_PROGRAMS=OFF', '-DOPUS_BUILD_TESTING=OFF',
                            '-DOPUS_INSTALL_PKG_CONFIG_MODULE=ON', '-DOPUS_DRED=OFF', '-DOPUS_DEEP_PLC=OFF', '-DOPUS_OSCE=OFF']),
        ('webp', cmake_lib, ['-DWEBP_BUILD_ANIM_UTILS=OFF', '-DWEBP_BUILD_CWEBP=OFF', '-DWEBP_BUILD_DWEBP=OFF',
                            '-DWEBP_BUILD_GIF2WEBP=OFF', '-DWEBP_BUILD_IMG2WEBP=OFF', '-DWEBP_BUILD_VWEBP=OFF',
                            '-DWEBP_BUILD_WEBPINFO=OFF', '-DWEBP_BUILD_WEBPMUX=OFF', '-DWEBP_BUILD_EXTRAS=OFF',
                            '-DWEBP_BUILD_LIBWEBPMUX=ON', '-DWEBP_BUILD_LIBWEBPDEMUX=ON']),
        ('freetype', cmake_lib, ['-DFT_DISABLE_ZLIB=ON', '-DFT_DISABLE_BZIP2=ON', '-DFT_DISABLE_PNG=ON',
                                '-DFT_DISABLE_HARFBUZZ=ON', '-DFT_DISABLE_BROTLI=ON']),
        ('fribidi', meson_lib, ['-Ddocs=false', '-Dbin=false', '-Dtests=false']),
        ('harfbuzz', meson_lib, ['-Dglib=disabled', '-Dgobject=disabled', '-Dcairo=disabled', '-Dicu=disabled',
                                '-Dgraphite2=disabled', '-Dfreetype=enabled', '-Dtests=disabled',
                                '-Ddocs=disabled', '-Dutilities=disabled', '-Dintrospection=disabled',
                                '-Dsubset=disabled', '-Draster=disabled', '-Dvector=disabled', '-Dgpu=disabled',
                                '-Dgpu_demo=disabled', '-Dchafa=disabled', '-Dpng=disabled', '-Dzlib=disabled']),
        ('unibreak', autoconf_lib, []),
        ('ass', meson_lib, ['-Dfontconfig=disabled', '-Ddirectwrite=disabled', '-Dcoretext=disabled',
                           '-Dtest=disabled', '-Dprofile=disabled', '-Dcompare=disabled', '-Dfuzz=disabled',
                           '-Dcheckasm=disabled', '-Dlibunibreak=enabled', '-Drequire-system-font-provider=false']),
    ]
    for name, builder, opts in builders:
        stamp = OUT / 'deps-obj' / target / name / 'helix-built.json'
        record = {'name': name, 'options': opts, 'source': json.loads((OUT / 'source-lock.json').read_text())[name]}
        if stamp.exists() and json.loads(stamp.read_text()) == record:
            print(json.dumps({'cached_dependency': name, 'target': target}), flush=True)
            continue
        builder(name, target, opts, jobs)
        stamp.write_text(json.dumps(record, indent=2) + '\n')


def build(target, profile, jobs):
    if hashlib.sha256((BASE / 'downloads/ffmpeg-9.0.2.tar.xz').read_bytes()).hexdigest() != '8c3850283eb25fa026482078a04051e0be17347b09ef81a0849bec15a96e002e':
        raise RuntimeError('FFmpeg pinned archive mismatch')
    prefix, env = env_for(target)
    if profile == 'extended':
        dependencies(target, jobs)
    spec = importlib.util.spec_from_file_location('helix_ffmpeg_lite', Path(__file__).with_name('ffmpeg-lite-experiment.py'))
    base = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(base)
    components = dict(base.COMPONENTS)
    for group in [COMMON] + ([EXTERNAL] if profile == 'extended' else []):
        for key, value in group.items():
            components[key] += ',' + value
    obj = OUT / 'obj' / profile / target
    install = OUT / 'install' / profile / target
    obj.mkdir(parents=True, exist_ok=True)
    flags = ['--disable-autodetect', '--disable-everything', '--disable-doc', '--disable-debug',
             '--disable-network', '--disable-avdevice', '--disable-programs', '--enable-ffmpeg', '--enable-ffprobe',
             '--disable-gpl', '--disable-nonfree', '--disable-version3', '--disable-static', '--enable-shared',
             '--disable-symver', '--enable-small', '--enable-pic', '--enable-pthreads', '--enable-zlib',
             f'--prefix={install}', f'--extra-cflags={CFLAGS} -I{prefix}/include']
    flags += [f'--enable-{key}={value}' for key, value in components.items()]
    linker = f'-L{prefix}/lib '
    if target == 'arm64-v8a':
        flags += ['--target-os=android', '--enable-cross-compile', '--arch=aarch64',
                  f'--cc={env["CC"]}', f'--cxx={env["CXX"]}', f'--ar={env["AR"]}',
                  f'--ranlib={env["RANLIB"]}', f'--strip={env["STRIP"]}', f'--nm={env["NM"]}',
                  '--enable-jni', '--enable-mediacodec', '--enable-decoder=h264_mediacodec,hevc_mediacodec',
                  '--enable-encoder=h264_mediacodec,hevc_mediacodec']
        linker += '-Wl,--gc-sections -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 -Wl,-z,relro -Wl,-z,now'
        if profile == 'extended':
            flags += ['--extra-libs=-lc++_shared']
    else:
        flags += ['--cc=clang', '--cxx=clang++']
        linker += '-Wl,-dead_strip'
        if profile == 'extended':
            flags += ['--extra-libs=-lc++']
    flags += [f'--extra-ldflags={linker}']
    if profile == 'extended':
        flags += ['--pkg-config-flags=--static', '--enable-libmp3lame', '--enable-libopus', '--enable-libwebp',
                  '--enable-libfreetype', '--enable-libharfbuzz', '--enable-libfribidi', '--enable-libass']
    else:
        env['PKG_CONFIG_LIBDIR'] = str(OUT / 'no-pkg-config')
    (obj / 'configure-argv.json').write_text(json.dumps(flags, indent=2) + '\n')
    started = time.monotonic()
    run([SOURCE / 'configure'] + flags, cwd=obj, env=env, log=f'{profile}-{target}-configure.log')
    run(['make', f'-j{jobs}'], cwd=obj, env=env, log=f'{profile}-{target}-build.log')
    run(['make', 'install'], cwd=obj, env=env, log=f'{profile}-{target}-install.log')
    result = {'target': target, 'profile': profile, 'components': components, 'seconds': round(time.monotonic()-started, 2)}
    (obj / 'build-result.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result), flush=True)

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--target', choices=['host', 'arm64-v8a'], default='arm64-v8a')
    parser.add_argument('--profile', choices=['common', 'extended'], default='extended')
    parser.add_argument('--jobs', type=int, default=6)
    args = parser.parse_args()
    if not 1 <= args.jobs <= 12:
        parser.error('jobs must be 1..12')
    build(args.target, args.profile, args.jobs)
