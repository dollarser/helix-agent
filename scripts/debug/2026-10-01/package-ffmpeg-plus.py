#!/usr/bin/env python3
"""Package the measured experimental runtime with licenses and reproducible recipes, never with font files."""
from __future__ import annotations
import hashlib
import json
import os
from pathlib import Path
import zipfile

ROOT=Path(__file__).resolve().parents[3]
OUT=ROOT/'build/ffmpeg-plus-size-2026-10-01'
BASE=ROOT/'build/ffmpeg-lite-size-2026-10-01'
SDK=Path(os.environ['ANDROID_HOME'])


def normalized(text):
    return text.replace(str(SDK),'${ANDROID_HOME}').replace(str(ROOT),'${PROJECT_ROOT}')


def main():
    size=json.loads((OUT/'size-extended-arm64-v8a.json').read_text())
    tests=json.loads((OUT/'android-extended-results.json').read_text())
    assert tests['passed']==17 and tests['failed']==0, 'No complete current Android acceptance record'
    entries={}
    for row in size['files']:
        name=row['file'];file=OUT/'payload/extended/arm64-v8a'/name
        data=file.read_bytes()
        assert hashlib.sha256(data).hexdigest()==row['sha256']
        prefix='lib' if name.endswith('.so') else 'tools'
        entries[f'{prefix}/arm64-v8a/{name}']=data
    legal={
        'ffmpeg/COPYING.LGPLv2.1':BASE/'source/ffmpeg-9.0.2/COPYING.LGPLv2.1',
        'ffmpeg/LICENSE.md':BASE/'source/ffmpeg-9.0.2/LICENSE.md',
        'lame/COPYING':OUT/'source/lame/COPYING',
        'lame/LICENSE':OUT/'source/lame/LICENSE',
        'opus/COPYING':OUT/'source/opus/COPYING',
        'webp/COPYING':OUT/'source/webp/COPYING',
        'webp/PATENTS':OUT/'source/webp/PATENTS',
        'freetype/LICENSE.TXT':OUT/'source/freetype/LICENSE.TXT',
        'freetype/FTL.TXT':OUT/'source/freetype/docs/FTL.TXT',
        'freetype/bdf-README':OUT/'source/freetype/src/bdf/README',
        'freetype/pcf-README':OUT/'source/freetype/src/pcf/README',
        'harfbuzz/COPYING':OUT/'source/harfbuzz/COPYING',
        'fribidi/COPYING':OUT/'source/fribidi/COPYING',
        'libunibreak/LICENCE':OUT/'source/unibreak/LICENCE',
        'libass/COPYING':OUT/'source/ass/COPYING',
    }
    for name,file in legal.items():
        entries['LICENSES/'+name]=file.read_bytes()
    for name in ['ffmpeg-lite-experiment.py','ffmpeg-plus-sources.py','build-ffmpeg-plus.py','measure-ffmpeg-plus.py',
                 'probe-ffmpeg-plus-android.py','probe-mediacodec-modes.py','package-ffmpeg-plus.py']:
        entries['scripts/debug/2026-10-01/'+name]=Path(__file__).with_name(name).read_bytes()
    entries['scripts/with-host-slot.py']=(ROOT/'scripts/with-host-slot.py').read_bytes()
    entries['metadata/dependency-sources.json']=(OUT/'source-lock.json').read_bytes()
    entries['metadata/ffmpeg-source-provenance.json']=(BASE/'source-provenance.json').read_bytes()
    entries['metadata/configure-argv.json']=normalized((OUT/'obj/extended/arm64-v8a/configure-argv.json').read_text()).encode()
    entries['metadata/config_components.h']=(OUT/'obj/extended/arm64-v8a/config_components.h').read_bytes()
    entries['metadata/runtime-size.json']=(OUT/'size-extended-arm64-v8a.json').read_bytes()
    entries['metadata/android-functional-tests.json']=(OUT/'android-extended-results.json').read_bytes()
    entries['metadata/mediacodec-mode-comparison.json']=(OUT/'mediacodec-mode-results.json').read_bytes()
    note='''FFmpeg Plus Android ARM64 experiment -- not an APK or installed Helix feature.

This package contains the exact FFmpeg 9.0.2 runtime closure measured and tested in this experiment.
Eight source dependencies are statically combined into the six shared FFmpeg libraries;
Android system libraries are not duplicated. No x264/x265, network protocols, capture devices,
font files, or GPL/nonfree/version3 FFmpeg components are included.

Libraries: FFmpeg (LGPL 2.1 or later), LAME (LGPL), Opus (BSD), libwebp (BSD),
FreeType (FreeType License selected), HarfBuzz (MIT), FriBidi (LGPL), libunibreak (zlib), libass (ISC).
See all upstream license texts. This software uses LAME (https://lame.sourceforge.io).
Portions use the FreeType Project (https://freetype.org); all rights and copyright notices are retained.

Corresponding unmodified upstream source releases and SHA256 hashes are in metadata/.
Full downloaded sources remain in the project's ignored build/ experiment directories.
The included recipes rebuild the runtime and dependencies. This is a private build experiment,
not a statement that a store release or a future packaging method has completed licensing review.
No new license right to unrelated fonts, codecs, or third-party services is asserted.

Tested: emulator-5554 / Android 16 API 36 / arm64-v8a / 4 KiB page size.
17 synthetic Android checks passed. Not tested: real-device performance, other OEMs,
actual 16 KiB device execution, multi-ABI packaging, or integrated application lifecycle/security.

MediaCodec encoding on this emulator requires the tested async recipe:
  -c:v h264_mediacodec -ndk_codec 1 -ndk_async 1 -flags -global_header -bsf:v extract_extradata
Replace h264_mediacodec with hevc_mediacodec for HEVC. Validate device capabilities and output.
Synchronous mode produced empty H.264 output / stalled HEVC here; do not treat exit 0 as completion.
There is no x264/x265 software fallback and no blanket promise about MediaCodec implementations.

Text rendering uses an explicit fontfile; subtitles use an explicit fontsdir and font family.
The test used the emulator's /system/fonts/NotoSansCJK-Regular.ttc without copying/distributing it.
An app must discover approved device/user font resources rather than assume every OEM has that path.

Runtime file sizes and ZIP sizes are measured; neither is a signed integrated Helix APK delta.
Source archives, build objects, and tools for rebuilding are not part of runtime payload size.
'''
    entries['NOTICE.txt']=note.encode()
    manifest={name:{'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest()} for name,data in entries.items()}
    entries['MANIFEST.json']=(json.dumps(manifest,indent=2)+'\n').encode()
    target=OUT/'packages/ffmpeg-9.0.2-plus-arm64-v8a.zip'
    with zipfile.ZipFile(target,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=9) as z:
        for name,data in sorted(entries.items()):
            assert Path(name).suffix.lower() not in {'.ttf','.otf','.ttc','.woff','.woff2'}
            item=zipfile.ZipInfo(name,(2026,10,1,0,0,0))
            item.compress_type=zipfile.ZIP_DEFLATED
            item.external_attr=(0o100755 if name.startswith(('lib/','tools/')) else 0o100644)<<16
            z.writestr(item,data)
    result={'file':str(target.relative_to(ROOT)),'bytes':target.stat().st_size,
            'sha256':hashlib.sha256(target.read_bytes()).hexdigest(),'entries':len(entries),'fonts_included':False}
    (OUT/'package-summary.json').write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(result,indent=2))

if __name__=='__main__':
    main()
