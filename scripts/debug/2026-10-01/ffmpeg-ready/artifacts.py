#!/usr/bin/env python3
"""Exact binary closure, compiled capability manifest and reproducible preparation bundles."""
from __future__ import annotations
import argparse
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import tempfile
import zipfile

from kit import HERE, ROOT, OUT, RECIPE, atomic_json, digest, identity, proof_matches, require, tree_manifest, validate_components

LIBS = {'libavcodec.so','libavformat.so','libavfilter.so','libavutil.so','libswscale.so','libswresample.so'}
SYSTEM = {'libc.so','libm.so','libdl.so','libz.so','liblog.so','libandroid.so','libmediandk.so'}


def verify_build(profile, target):
    proof_path = OUT/'proof'/f'{profile}-{target}.json'
    proof = json.loads(proof_path.read_text())
    require(proof['toolchain']['builder_sha256'] == digest(HERE/'build.py'), 'Builder changed after compilation')
    require(proof['toolchain']['kit_sha256'] == digest(HERE/'kit.py'), 'Build helpers changed after compilation')
    require(proof['recipe_sha256'] == digest(HERE/'recipe.json'), 'Build belongs to another recipe')
    require(proof_matches(proof_path,proof['key'],OUT/'install'/profile/target), 'Build outputs changed or incomplete')
    obj=OUT/'obj'/profile/target
    require(proof['config_sha256'] == digest(obj/'config_components.h'), 'Compiled component evidence changed')
    caps=validate_components((obj/'config_components.h').read_text(),(obj/'config.h').read_text(),profile,target)
    return proof,caps


def zip_bytes(path: Path, entries: dict[str,bytes], compressed=True):
    path.parent.mkdir(parents=True,exist_ok=True)
    fd,temporary=tempfile.mkstemp(prefix='.'+path.name+'-',dir=path.parent)
    os.close(fd)
    try:
        with zipfile.ZipFile(temporary,'w',compression=zipfile.ZIP_DEFLATED if compressed else zipfile.ZIP_STORED,compresslevel=9) as bundle:
            for name,data in sorted(entries.items()):
                require(Path(name).suffix.lower() not in {'.ttf','.otf','.ttc','.woff','.woff2'},'Do not bundle font files')
                record=zipfile.ZipInfo(name,(2026,10,1,0,0,0))
                record.compress_type=zipfile.ZIP_DEFLATED if compressed else zipfile.ZIP_STORED
                record.external_attr=(0o100755 if name.startswith(('lib/','tools/')) else 0o100644)<<16
                bundle.writestr(record,data)
        with open(temporary,'rb') as stream:
            os.fsync(stream.fileno())
        os.replace(temporary,path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)
    return {'bytes':path.stat().st_size,'sha256':digest(path),'path':str(path.relative_to(ROOT))}


def measure(profile):
    proof,caps=verify_build(profile,'arm64-v8a')
    sdk=Path(os.environ.get('ANDROID_HOME',Path.home()/'Library/Android/sdk'))
    tag='darwin-x86_64' if platform.system()=='Darwin' else 'linux-x86_64'
    ndk=sdk/'ndk'/RECIPE['ndk']/'toolchains/llvm/prebuilt'/tag
    installed=OUT/'install'/profile/'arm64-v8a'
    require({p.name for p in (installed/'lib').glob('*.so')} == LIBS,'Unexpected library inventory')
    payload=OUT/'payload'/profile
    payload.parent.mkdir(parents=True,exist_ok=True)
    rows=[]
    with tempfile.TemporaryDirectory(prefix=profile+'-',dir=payload.parent) as tmp:
        staging=Path(tmp)
        pending=[installed/'lib'/name for name in sorted(LIBS)]+[installed/'bin/ffmpeg',installed/'bin/ffprobe']
        seen=set()
        while pending:
            original=pending.pop(0)
            if original.name in seen:
                continue
            seen.add(original.name)
            require(original.is_file() and not original.is_symlink(),'Invalid runtime file: '+str(original))
            file=staging/original.name
            shutil.copyfile(original,file)
            subprocess.run([ndk/'bin/llvm-strip','--strip-unneeded',file],check=True,timeout=30)
            elf=subprocess.check_output([ndk/'bin/llvm-readelf','-h','-lW','-dW',file],text=True,timeout=30)
            (staging/(file.name+'.elf.txt')).write_text(elf)
            require('AArch64' in elf and 'ELF64' in elf,'Wrong ABI')
            require('(RPATH)' not in elf and '(RUNPATH)' not in elf,'Unexpected runtime search path')
            align=[int(line.split()[-1],16) for line in elf.splitlines() if line.strip().startswith('LOAD ')]
            require(bool(align) and min(align)>=16384,'Missing 16KiB LOAD alignment')
            needed=re.findall(r'\(NEEDED\).*?\[([^]]+)\]',elf)
            for dep in set(needed)-SYSTEM-seen:
                if dep in LIBS:
                    pending.append(installed/'lib'/dep)
                elif dep=='libc++_shared.so':
                    pending.append(ndk/'sysroot/usr/lib/aarch64-linux-android'/dep)
                else:
                    raise RuntimeError('Unaccounted dependency: '+dep)
            rows.append({'name':file.name,'bytes':file.stat().st_size,'sha256':digest(file),'needed':needed,'alignment':align})
        if payload.exists():
            require(not payload.is_symlink(),'Unexpected payload symlink')
            shutil.rmtree(payload)
        shutil.copytree(staging,payload)
    # Exactly these files, never glob leftovers from a previous build.
    entries={('lib' if r['name'].endswith('.so') else 'tools')+'/arm64-v8a/'+r['name']:(payload/r['name']).read_bytes() for r in rows}
    packages=OUT/'packages'
    compressed=zip_bytes(packages/f'{profile}-payload.zip',entries)
    stored=zip_bytes(packages/f'{profile}-payload-stored.zip',entries,False)
    aligned=packages/f'{profile}-payload-stored-16k.zip'
    aligner=sdk/'build-tools/36.0.0/zipalign'
    subprocess.run([aligner,'-f','-P','16','4',ROOT/stored['path'],aligned],check=True,timeout=30)
    subprocess.run([aligner,'-c','-P','16','4',aligned],check=True,timeout=30)
    total=sum(r['bytes'] for r in rows)
    result={'schema':1,'profile':profile,'target':'arm64-v8a','api':29,'build_identity':proof['key'],
            'artifact_identity':identity({'build':proof['key'],'files':rows}),
            'recipe_sha256':digest(HERE/'recipe.json'),'files':rows,'runtime_bytes':total,
            'payload_zip':compressed,'stored_aligned_bytes':aligned.stat().st_size,
            'limit_bytes':RECIPE['limit_bytes'],'under_limit':total<RECIPE['limit_bytes'] and aligned.stat().st_size<RECIPE['limit_bytes'],
            'capabilities':caps,'device_status':'not_requested','apk_integration':False,'fonts_bundled':False}
    atomic_json(OUT/f'manifest-{profile}.json',result)
    print(json.dumps({k:v for k,v in result.items() if k not in ('files','capabilities')},ensure_ascii=False),flush=True)
    return result


def validate_test_evidence(tests: dict, proof: dict, expected_profile: str):
    require(tests.get('profile') == expected_profile and tests.get('build_identity') == proof['key'], 'Tests belong to a different binary build')
    require(tests.get('install_identity') == identity(proof['files']), 'Tests do not bind the current runtime files')
    require(tests.get('suite_sha256') == digest(HERE/'test_media.py'), 'Test suite changed after validation')
    require(tests.get('contract_sha256') == digest(HERE/'contract.py'), 'Preflight contract changed after validation')
    require(tests.get('contract_test_sha256') == digest(HERE/'test_contract.py'), 'Boundary tests changed after validation')
    require(tests.get('artifact_tools_sha256') == digest(HERE/'artifacts.py'), 'Artifact verifier changed after validation')
    require(tests.get('recipe_sha256') == digest(HERE/'recipe.json'), 'Tests belong to a different component recipe')
    results=tests.get('tests',[])
    require(results and tests.get('failed') == 0 and tests.get('skipped') == 0,'No complete current functional result')
    require(tests.get('test_count') == len(results) and all(x.get('status') == 'passed' for x in results), 'Incomplete per-test evidence')
    require(len({x['name'] for x in results}) == len(results),'Duplicate test evidence')
    require(sorted(tests.get('planned_tests',[])) == sorted(x['name'] for x in results),'Missing planned test results')


def package(profile):
    manifest=measure(profile)
    require(manifest['under_limit'],'Candidate exceeds agreed 25MB ceiling')
    proof,_=verify_build(profile,'host')
    tests=json.loads((OUT/f'host-results-{profile}.json').read_text())
    validate_test_evidence(tests,proof,profile)
    payload=OUT/'payload'/profile
    entries={}
    for row in manifest['files']:
        file=payload/row['name']
        require(digest(file)==row['sha256'],'Payload changed after measurement')
        prefix='lib' if file.suffix=='.so' else 'tools'
        entries[f'{prefix}/arm64-v8a/{file.name}']=file.read_bytes()
    licenses={
        'ffmpeg':['COPYING.LGPLv2.1','LICENSE.md'],'lame':['COPYING','LICENSE'],
        'opus':['COPYING'],'webp':['COPYING','PATENTS'],
        'freetype':['LICENSE.TXT','docs/FTL.TXT','src/bdf/README','src/pcf/README'],
        'harfbuzz':['COPYING'],'fribidi':['COPYING'],'unibreak':['LICENCE'],'ass':['COPYING']}
    if profile=='ready-av1':
        licenses['dav1d']=['COPYING']
    for name,paths in licenses.items():
        for path in paths:
            entries[f'LICENSES/{name}/{path}']=(OUT/'source'/name/path).read_bytes()
    for path in sorted(HERE.glob('*.py'))+sorted(HERE.glob('*.json')):
        entries['scripts/debug/2026-10-01/ffmpeg-ready/'+path.name]=path.read_bytes()
    entries['scripts/debug/2026-10-01/build-ffmpeg-plus.py']=(HERE.parent/'build-ffmpeg-plus.py').read_bytes()
    entries['scripts/with-host-slot.py']=(ROOT/'scripts/with-host-slot.py').read_bytes()
    entries['metadata/candidate.json']=(json.dumps(manifest,indent=2)+'\n').encode()
    entries['metadata/host-tests.json']=(json.dumps(tests,indent=2)+'\n').encode()
    entries['metadata/input-sources.json']=(json.dumps(RECIPE['sources'],indent=2)+'\n').encode()
    note=(HERE/'NOTICE.txt').read_bytes()
    entries['NOTICE.txt']=note
    inventory={name:{'bytes':len(data),'sha256':__import__('hashlib').sha256(data).hexdigest()} for name,data in entries.items()}
    entries['MANIFEST.json']=(json.dumps(inventory,indent=2)+'\n').encode()
    result=zip_bytes(OUT/'packages'/f'ffmpeg-9.0.2-{profile}-arm64-v8a.zip',entries)
    # Independently inspect the written archive and verify the exact entry set and all digests.
    with zipfile.ZipFile(ROOT/result['path']) as bundle:
        require(set(bundle.namelist())==set(entries),'Package inventory mismatch')
        require(len(bundle.namelist())==len(entries),'Duplicate archive entries')
        for name,record in inventory.items():
            data=bundle.read(name)
            require(len(data)==record['bytes'] and __import__('hashlib').sha256(data).hexdigest()==record['sha256'],'Package integrity failure')
    atomic_json(OUT/f'package-{profile}.json',result)
    print(json.dumps(result),flush=True)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action',choices=['measure','package'])
    parser.add_argument('--profile',choices=['ready','ready-av1'],default='ready')
    args=parser.parse_args()
    if args.action=='measure':
        measure(args.profile)
    else:
        package(args.profile)
