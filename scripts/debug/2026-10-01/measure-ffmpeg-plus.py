#!/usr/bin/env python3
"""Measure all runtime files of the isolated Android FFmpeg experiment."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / 'build/ffmpeg-plus-size-2026-10-01'
SDK = Path(os.environ['ANDROID_HOME'])
NDK = SDK / 'ndk/28.2.13676358/toolchains/llvm/prebuilt/darwin-x86_64'
SYSTEM = {'libc.so','libm.so','libdl.so','libz.so','liblog.so','libandroid.so','libmediandk.so'}
EXPECTED = {'libavcodec.so','libavformat.so','libavfilter.so','libavutil.so','libswscale.so','libswresample.so'}

def main(profile):
    prefix = OUT / 'install' / profile / 'arm64-v8a'
    stage = OUT / 'payload' / profile / 'arm64-v8a'
    stage.mkdir(parents=True, exist_ok=True)
    libraries = sorted((prefix/'lib').glob('*.so'))
    assert {p.name for p in libraries} == EXPECTED
    pending = libraries + [prefix/'bin/ffmpeg',prefix/'bin/ffprobe']
    seen, rows = set(), []
    while pending:
        original = pending.pop(0)
        if original.name in seen:
            continue
        seen.add(original.name)
        file = stage / original.name
        shutil.copy2(original,file)
        subprocess.run([NDK/'bin/llvm-strip','--strip-unneeded',file],check=True)
        elf = subprocess.check_output([NDK/'bin/llvm-readelf','-h','-lW','-dW',file],text=True)
        (stage/(file.name+'.elf.txt')).write_text(elf)
        assert 'AArch64' in elf and '/Users/' not in elf
        loads = [int(line.split()[-1],16) for line in elf.splitlines() if line.strip().startswith('LOAD ')]
        assert loads and min(loads)>=16384
        needs = re.findall(r'\(NEEDED\).*?\[([^]]+)\]',elf)
        for dep in set(needs)-SYSTEM-seen:
            if dep in EXPECTED:
                pending.append(prefix/'lib'/dep)
            elif dep=='libc++_shared.so':
                pending.append(NDK/'sysroot/usr/lib/aarch64-linux-android'/dep)
            else:
                raise RuntimeError(f'Unaccounted dependency: {file.name}: {dep}')
        rows.append({'file':file.name,'bytes':file.stat().st_size,
                     'sha256':hashlib.sha256(file.read_bytes()).hexdigest(),'needed':needs,'alignments':loads})
    packages = OUT/'packages'
    packages.mkdir(exist_ok=True)
    packed = {}
    for label,method in [('deflated',zipfile.ZIP_DEFLATED),('stored',zipfile.ZIP_STORED)]:
        file = packages/f'ffmpeg-plus-{profile}-arm64-v8a-{label}.zip'
        with zipfile.ZipFile(file,'w',compression=method,compresslevel=9) as z:
            for row in rows:
                name=row['file']
                entry=f'lib/arm64-v8a/{name}' if name.endswith('.so') else f'tools/arm64-v8a/{name}'
                z.write(stage/name,entry)
        packed[label]={'file':str(file.relative_to(ROOT)),'bytes':file.stat().st_size}
        if label=='stored':
            aligned=file.with_name(file.stem+'-16k.zip')
            tool=SDK/'build-tools/36.0.0/zipalign'
            subprocess.run([tool,'-f','-P','16','4',file,aligned],check=True)
            subprocess.run([tool,'-c','-P','16','4',aligned],check=True)
            packed['stored_16k']={'file':str(aligned.relative_to(ROOT)),'bytes':aligned.stat().st_size}
    config=(OUT/'obj'/profile/'arm64-v8a/config_components.h').read_text()
    required=['CONCAT_DEMUXER','SEGMENT_MUXER','FADE_FILTER','LOUDNORM_FILTER','SILENCEDETECT_FILTER',
              'SILENCEREMOVE_FILTER','H264_MEDIACODEC_ENCODER','HEVC_MEDIACODEC_ENCODER',
              'MOVTEXT_ENCODER','MOVTEXT_DECODER','PCM_S16LE_MUXER']
    if profile=='extended':
        required+=['LIBMP3LAME_ENCODER','LIBOPUS_ENCODER','LIBWEBP_ENCODER','DRAWTEXT_FILTER','SUBTITLES_FILTER','ASS_FILTER']
    assert all(f'#define CONFIG_{item} 1' in config for item in required)
    flags=(OUT/'obj'/profile/'arm64-v8a/config.h').read_text()
    assert all(f'#define CONFIG_{item} 0' in flags for item in ['GPL','NONFREE','VERSION3','NETWORK','AVDEVICE'])
    total=sum(row['bytes'] for row in rows)
    result={'profile':profile,'target':'arm64-v8a','runtime_total_bytes':total,'threshold_bytes':25000000,
            'under_threshold':total<25000000,'files':rows,'packages':packed,
            'fonts_bundled':False,'apk_integration':False,
            'notice':'Size-test archives only. Source licenses must accompany any distribution. No fonts included.'}
    (OUT/f'size-{profile}-arm64-v8a.json').write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps({k:v for k,v in result.items() if k!='files'},indent=2))

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--profile',choices=['common','extended'],default='extended')
    main(parser.parse_args().profile)
