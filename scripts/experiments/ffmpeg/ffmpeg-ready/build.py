#!/usr/bin/env python3
"""Build independent Ready/Ready+AV1 candidates. No Gradle, app edits, ADB, or production registration."""
from __future__ import annotations

import argparse
import importlib.util
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import tarfile
import tempfile
import venv

from kit import HERE, ROOT, OUT, RECIPE, atomic_json, components, digest, identity, proof_matches, require, run, tree_manifest, validate_components

HELPER = HERE.parent / 'build-ffmpeg-plus.py'
HELPER_SHA = 'b688d55f8341221d352f5bdbce499f259517f4ce6ed121a70847274cd29d14c1'
DEPS = [name for name in RECIPE['sources'] if name != 'ffmpeg']


def archive_for(name: str) -> Path:
    entry = RECIPE['sources'][name]
    archive = OUT / 'downloads' / entry['url'].rsplit('/', 1)[1]
    archive.parent.mkdir(parents=True, exist_ok=True)
    if not archive.exists():
        candidates = [ROOT/'build/ffmpeg-plus-size-2026-10-01/downloads'/archive.name,
                      ROOT/'build/ffmpeg-lite-size-2026-10-01/downloads'/archive.name]
        cached = next((path for path in candidates if path.is_file() and digest(path) == entry['sha256']), None)
        partial = archive.with_suffix(archive.suffix + '.partial')
        if cached:
            shutil.copyfile(cached, partial)
        else:
            run(['curl', '--fail', '--location', '--proto', '=https', '--proto-redir', '=https',
                 '--connect-timeout', '20', '--max-time', '180', '--retry', '2', '-o', partial, entry['url']],
                log=OUT/'logs'/f'download-{name}.log', timeout=600)
        require(digest(partial) == entry['sha256'], f'Pinned archive mismatch: {name}')
        os.replace(partial, archive)
    require(not archive.is_symlink() and digest(archive) == entry['sha256'], f'Changed archive: {name}')
    return archive


def snapshot(name: str) -> str:
    archive = archive_for(name)
    destination = OUT / 'source' / name
    proof = OUT / 'source-proof' / f'{name}.json'
    key = RECIPE['sources'][name]['sha256']
    if destination.exists():
        require(proof_matches(proof, key, destination), f'Extracted source modified: {name}; do not compile unverified edits')
        return key
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='extract-', dir=destination.parent) as directory:
        staging = Path(directory)
        with tarfile.open(archive) as bundle:
            # Python data filter rejects special files and outside-root links/path escapes.
            bundle.extractall(staging, filter='data')
        roots = list(staging.iterdir())
        require(len(roots) == 1 and roots[0].is_dir() and not roots[0].is_symlink(), f'Unexpected archive root: {name}')
        roots[0].rename(destination)
    atomic_json(proof, {'key': key, 'files': tree_manifest(destination)})
    return key


def prepare() -> None:
    for name in RECIPE['sources']:
        snapshot(name)
        print(json.dumps({'verified_source': name}), flush=True)
    tools = OUT / 'tools-venv'
    if not (tools/'bin/python').is_file():
        venv.EnvBuilder(with_pip=True).create(tools)
    run([tools/'bin/python', '-m', 'pip', 'install', '--disable-pip-version-check',
         'meson==1.9.1', 'ninja==1.13.0'], log=OUT/'logs/build-tools.log')
    atomic_json(OUT/'source-lock.json', RECIPE['sources'])


def helper():
    require(digest(HELPER) == HELPER_SHA, 'Reviewed dependency builder changed; re-review before using it')
    spec = importlib.util.spec_from_file_location('reviewed_plus_dependency_builder', HELPER)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    module.OUT = OUT
    module.SOURCE = OUT/'source/ffmpeg'
    module.VENV = OUT/'tools-venv/bin'
    original_env = module.env_for
    def clean_env(target):
        prefix, env = original_env(target)
        for key in ('CPATH','C_INCLUDE_PATH','CPLUS_INCLUDE_PATH','LIBRARY_PATH','CONFIG_SITE',
                    'DYLD_LIBRARY_PATH','DYLD_INSERT_LIBRARIES','LD_PRELOAD'):
            env.pop(key, None)
        return prefix, env
    module.env_for = clean_env
    def logged(argv, *, cwd=None, env=None, log, timeout=1200):
        print(json.dumps({'phase': log, 'started': True}), flush=True)
        result = run(argv, cwd=cwd, env=env, log=OUT/'logs'/log, timeout=timeout)
        print(json.dumps({'phase': log, 'passed': True}), flush=True)
        return result
    module.run = logged
    return module


def toolchain(module, target: str) -> dict:
    _, env = module.env_for(target)
    result = {'target': target, 'host_system': platform.system(), 'host_machine': platform.machine(),
              'ndk': RECIPE['ndk'], 'api': RECIPE['api'],
              'builder_sha256': digest(HERE/'build.py'), 'kit_sha256': digest(HERE/'kit.py'),
              'dependency_builder_sha256': digest(HELPER), 'recipe_sha256': digest(HERE/'recipe.json')}
    for key, executable in [('cc',env['CC']),('cxx',env['CXX']),('cmake','cmake'),
                            ('meson',str(module.VENV/'meson')),('ninja',str(module.VENV/'ninja')),('pkg-config','pkg-config')]:
        path = Path(shutil.which(executable) or executable)
        require(path.is_file(), f'Missing build tool: {executable}')
        result[key] = {'sha256': digest(path), 'version': subprocess.check_output([str(path), '--version'], text=True, timeout=20).splitlines()[0]}
    if target != 'host':
        result['ndk_source_properties'] = digest(module.NDK/'source.properties')
    return result


def remove_owned(path: Path) -> None:
    require(path.resolve().is_relative_to(OUT.resolve()) and path != OUT, 'Refuse removal outside experiment')
    if path.exists():
        require(not path.is_symlink(), 'Refuse recursive removal of a symlink')
        shutil.rmtree(path)


def build_deps(module, target: str, jobs: int, tools: dict) -> None:
    inputs = {name: snapshot(name) for name in DEPS}
    key = identity({'sources':inputs,'toolchain':tools})
    prefix = OUT/'deps'/target
    proof = OUT/'proof'/f'deps-{target}.json'
    if proof_matches(proof, key, prefix):
        print(json.dumps({'verified_dependency_cache':target}), flush=True)
        return
    # A miss invalidates both old objects and installed outputs; never reuse partial/foreign archives.
    remove_owned(OUT/'deps-obj'/target)
    remove_owned(prefix)
    module.dependencies(target, jobs)
    module.meson_lib('dav1d', target, ['-Denable_tools=false','-Denable_tests=false'], jobs)
    for name in DEPS:
        snapshot(name)
    atomic_json(proof, {'key':key,'inputs':inputs,'toolchain':tools,'files':tree_manifest(prefix)})


def build(target: str, profile: str, jobs: int) -> None:
    require((OUT/'tools-venv/bin/meson').is_file(), 'Run build.py prepare first')
    snapshot('ffmpeg')
    module = helper()
    tools = toolchain(module, target)
    build_deps(module, target, jobs, tools)
    prefix, env = module.env_for(target)
    install = OUT/'install'/profile/target
    obj = OUT/'obj'/profile/target
    required = components(profile, target)
    flags = ['--disable-autodetect','--disable-everything','--disable-doc','--disable-debug',
             '--disable-network','--disable-avdevice','--disable-programs','--enable-ffmpeg','--enable-ffprobe',
             '--disable-gpl','--disable-nonfree','--disable-version3','--disable-static','--enable-shared',
             '--disable-symver','--enable-small','--enable-pic','--enable-pthreads','--enable-zlib',
             f'--prefix={install}',f'--extra-cflags={module.CFLAGS} -I{prefix}/include',
             '--pkg-config-flags=--static','--enable-libmp3lame','--enable-libopus','--enable-libwebp',
             '--enable-libfreetype','--enable-libharfbuzz','--enable-libfribidi','--enable-libass']
    flags += [f'--enable-{kind}=' + ','.join(names) for kind,names in required.items()]
    linker = f'-L{prefix}/lib '
    if target == 'arm64-v8a':
        flags += ['--target-os=android','--enable-cross-compile','--arch=aarch64',
                  f'--cc={env["CC"]}',f'--cxx={env["CXX"]}',f'--ar={env["AR"]}',
                  f'--ranlib={env["RANLIB"]}',f'--strip={env["STRIP"]}',f'--nm={env["NM"]}',
                  '--enable-jni','--enable-mediacodec','--extra-libs=-lc++_shared']
        linker += '-Wl,--gc-sections -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 -Wl,-z,relro -Wl,-z,now'
    else:
        require(platform.system() == 'Darwin', 'Host companion currently validated only on macOS')
        flags += ['--cc=clang','--cxx=clang++','--extra-libs=-lc++']
        linker += '-Wl,-dead_strip'
    flags += [f'--extra-ldflags={linker}']
    if profile == 'ready-av1':
        flags += ['--enable-libdav1d']
    deps = json.loads((OUT/'proof'/f'deps-{target}.json').read_text())
    key = identity({'flags':flags,'toolchain':tools,'dependencies':deps,'source':RECIPE['sources']['ffmpeg']})
    proof = OUT/'proof'/f'{profile}-{target}.json'
    if proof_matches(proof, key, install):
        require((obj/'config_components.h').is_file(), 'Missing component evidence')
        validate_components((obj/'config_components.h').read_text(),(obj/'config.h').read_text(),profile,target)
        print(json.dumps({'verified_build_cache':profile,'target':target}),flush=True)
        return
    remove_owned(obj)
    remove_owned(install)
    obj.mkdir(parents=True)
    atomic_json(obj/'configure-argv.json',flags)
    module.run([OUT/'source/ffmpeg/configure']+flags,cwd=obj,env=env,log=f'{profile}-{target}-configure.log')
    inventory = validate_components((obj/'config_components.h').read_text(),(obj/'config.h').read_text(),profile,target)
    module.run(['make',f'-j{jobs}'],cwd=obj,env=env,log=f'{profile}-{target}-build.log')
    module.run(['make','install'],cwd=obj,env=env,log=f'{profile}-{target}-install.log')
    snapshot('ffmpeg')
    atomic_json(obj/'capabilities.json',inventory)
    atomic_json(proof,{'key':key,'profile':profile,'target':target,'toolchain':tools,
                      'recipe_sha256':digest(HERE/'recipe.json'),'config_sha256':digest(obj/'config_components.h'),
                      'files':tree_manifest(install),'device_status':'not_requested'})
    print(json.dumps({'built':profile,'target':target,'build_identity':key}),flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action',choices=['prepare','build'])
    parser.add_argument('--target',choices=['host','arm64-v8a'],default='arm64-v8a')
    parser.add_argument('--profile',choices=['ready','ready-av1'],default='ready')
    parser.add_argument('--jobs',type=int,default=6)
    args = parser.parse_args()
    require(1 <= args.jobs <= 12,'jobs must be 1..12')
    if args.action == 'prepare':
        prepare()
    else:
        build(args.target,args.profile,args.jobs)
