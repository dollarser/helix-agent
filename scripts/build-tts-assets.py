#!/usr/bin/env python3
"""Build pinned ASR+Melo JNI without eSpeak/Piper; model remains an explicit optional download."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / 'build/tts'
spec = importlib.util.spec_from_file_location('asr_build', ROOT / 'scripts/build-asr-assets.py')
asr = importlib.util.module_from_spec(spec)
spec.loader.exec_module(asr)


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def restrict_to_melo(source):
    """Apache-2.0 upstream source patch. Unsupported phonemizers are removed, not stubbed."""
    path = source / 'CMakeLists.txt'
    text = path.read_text()
    start = text.index('if(SHERPA_ONNX_ENABLE_TTS)\n  include(espeak-ng-for-piper)')
    end = text.index('endif()', start) + len('endif()')
    path.write_text(text[:start] + '# Helix: Melo frontend only; no eSpeak/Piper dependency.\n' + text[end:])
    path = source / 'sherpa-onnx/csrc/CMakeLists.txt'
    text = path.read_text()
    removed = ['hifigan-vocoder.cc', 'kokoro-multi-lang-lexicon.cc', 'matcha-tts-lexicon.cc',
               'piper-phonemize-lexicon.cc', 'vocoder.cc', 'vocos-vocoder.cc',
               'offline-tts-supertonic-impl.cc', 'offline-tts-supertonic-unicode-processor.cc']
    removed += ['offline-tts-'+name+'-model.cc' for name in ['kitten','kokoro','matcha','pocket','supertonic','zipvoice']]
    for name in removed:
        text = text.replace('    '+name+'\n', '')
    text = text.replace('if(SHERPA_ONNX_ENABLE_TTS)\n  target_link_libraries(sherpa-onnx-core\n    piper_phonemize)\nendif()', '')
    path.write_text(text)
    path = source / 'sherpa-onnx/csrc/offline-tts-impl.cc'
    text = path.read_text()
    for name in ['kitten','kokoro','matcha','pocket','supertonic','zipvoice']:
        text = text.replace('#include "sherpa-onnx/csrc/offline-tts-'+name+'-impl.h"\n', '')
    # Both file and AssetManager factories retain the original VITS constructor only.
    text, count = re.subn(r'  } else if \(!config\.model\.matcha\.acoustic_model\.empty\(\)\) \{.*?(?=\n\n  SHERPA_ONNX_LOGE)',
                          '  }', text, flags=re.S)
    if count != 2: raise ValueError('Pinned TTS factory changed')
    path.write_text(text)
    path = source / 'sherpa-onnx/csrc/offline-tts-vits-impl.h'
    text = path.read_text().replace('#include "sherpa-onnx/csrc/piper-phonemize-lexicon.h"\n', '')
    text, count = re.subn(r'    } else if \(\(meta_data\.is_piper.*?(?=    } else \{)', '', text, flags=re.S)
    if count != 2: raise ValueError('Pinned VITS frontend changed')
    text = text.replace('SHERPA_ONNX_LOGE("Failed to convert %s to token IDs", text.c_str());',
                        'SHERPA_ONNX_LOGE("Failed to convert text to token IDs");')
    path.write_text(text)
    path = source / 'sherpa-onnx/csrc/melo-tts-lexicon.cc'
    text = path.read_text()
    text = text.replace('SHERPA_ONNX_LOGE("Ignore OOV \'%{public}s\'", w.c_str());',
                        'SHERPA_ONNX_LOGE("Ignored out-of-vocabulary input");')
    text = text.replace('SHERPA_ONNX_LOGE("Ignore OOV \'%s\'", w.c_str());',
                        'SHERPA_ONNX_LOGE("Ignored out-of-vocabulary input");')
    path.write_text(text)


def signature():
    return {'recipe':sha(Path(__file__)), 'asrLock':sha(ROOT/'config/asr/dependencies.lock.json'),
            'modelLock':sha(ROOT/'config/tts/model.lock.json')}


def verify():
    manifest=json.loads((BUILD/'manifest.json').read_text())
    if manifest['inputs'] != signature(): raise ValueError('Run scripts/build-tts-assets.py after input changes')
    for name, item in manifest['outputs'].items():
        path=BUILD/name
        if path.stat().st_size != item['size'] or sha(path) != item['sha256']:
            raise ValueError('TTS artifact mismatch: '+name)
    print('Pinned combined JNI/model integrity verified; no eSpeak/Piper linked')


def build():
    BUILD.mkdir(parents=True,exist_ok=True)
    asr.verify()
    lock=json.loads((ROOT/'config/tts/model.lock.json').read_text())
    for name, item in lock['files'].items():
        path=BUILD/'model'/name
        if not asr.matches(path,item['sha256'],item['size']):
            asr.fetch(item['url'],path,item['sha256'],item['size'])
    asr.unpack_source(ROOT/'build/asr/source.tar.gz', BUILD/'source')
    restrict_to_melo(BUILD/'source')
    sdk=Path(os.environ.get('ANDROID_HOME', Path.home()/'Library/Android/sdk'))
    cmake=sdk/'cmake/3.31.6/bin/cmake'
    nm_tools=list((sdk/'ndk/28.2.13676358/toolchains/llvm/prebuilt').glob('*/bin/llvm-nm'))
    if len(nm_tools) != 1: raise ValueError('Expected one installed host NDK llvm-nm')
    old=ROOT/'build/asr'
    for abi in asr.ABIS:
        env=dict(os.environ, SHERPA_ONNXRUNTIME_LIB_DIR=str(old/'ort/jni'/abi),
                 SHERPA_ONNXRUNTIME_INCLUDE_DIR=str(old/'ort/headers'))
        args=[str(cmake),'-S',str(BUILD/'source'),'-B',str(BUILD/'native'/abi),'-G','Ninja',
              '-DCMAKE_MAKE_PROGRAM='+str(sdk/'cmake/3.31.6/bin/ninja'),
              '-DCMAKE_TOOLCHAIN_FILE='+str(sdk/'ndk/28.2.13676358/build/cmake/android.toolchain.cmake'),
              '-DANDROID_ABI='+abi,'-DANDROID_PLATFORM=android-29','-DANDROID_STL=c++_static',
              '-DCMAKE_BUILD_TYPE=Release','-DBUILD_SHARED_LIBS=ON','-DCMAKE_CXX_FLAGS=-DEIGEN_MPL2_ONLY']
        # Reuse the already pinned ASR dependencies; no second mutable fetch path.
        for folder in (old/'native'/abi/'_deps').glob('*-src'):
            name=folder.name.removesuffix('-src').upper()
            args.append('-DFETCHCONTENT_SOURCE_DIR_'+name+'='+str(folder))
        flags={'TTS':'ON','SPEAKER_DIARIZATION':'OFF','BINARY':'OFF','C_API':'OFF','PORTAUDIO':'OFF',
               'WEBSOCKET':'OFF','JNI':'ON','PYTHON':'OFF','TESTS':'OFF','CHECK':'OFF'}
        args += [f'-DSHERPA_ONNX_ENABLE_{k}={v}' for k,v in flags.items()]
        subprocess.run(args,env=env,check=True)
        subprocess.run([str(cmake),'--build',str(BUILD/'native'/abi),'--target','sherpa-onnx-jni',
                        '-j','4'],env=env,check=True)
        library=BUILD/'native'/abi/'lib/libsherpa-onnx-jni.so'
        symbols=subprocess.check_output([str(nm_tools[0]),
                                         '-D',str(library)],text=True)
        if re.search(r'\b(espeak_|piper_phonemize)',symbols): raise ValueError('Forbidden phonemizer linked')
        if 'OfflineTts_newFromFile' not in symbols: raise ValueError('TTS JNI absent')
    with zipfile.ZipFile(old/'sherpa.aar') as original, zipfile.ZipFile(BUILD/'speech.aar','w',zipfile.ZIP_DEFLATED) as out:
        for name in ['AndroidManifest.xml','classes.jar','R.txt','META-INF/com/android/build/gradle/aar-metadata.properties']:
            out.writestr(name,original.read(name))
        for abi in asr.ABIS:
            out.write(BUILD/'native'/abi/'lib/libsherpa-onnx-jni.so',f'jni/{abi}/libsherpa-onnx-jni.so')
            out.write(old/'ort/jni'/abi/'libonnxruntime.so',f'jni/{abi}/libonnxruntime.so')
        out.writestr('proguard.txt','-keep class com.k2fsa.sherpa.onnx.** { *; }\n')
    assets=BUILD/'assets/tts-licenses'; assets.mkdir(parents=True,exist_ok=True)
    shutil.copyfile(BUILD/'source/LICENSE',assets/'sherpa-Apache-2.0.txt')
    shutil.copyfile(BUILD/'model/LICENSE',assets/'Melo-MIT.txt')
    (assets/'BUILD.txt').write_text('sherpa-onnx 1.13.8; ASR + Melo VITS only. eSpeak/Piper and other TTS models removed.\n'
        'Recipe: scripts/build-tts-assets.py. Upstream source URL/hash: config/asr/dependencies.lock.json.\n'
        'ASR-only BUILD notice describes the standalone check artifact; the APK uses this combined JNI.\n'
        'Shared dependencies/notices: asr-licenses. Model/lexicon/rules: config/tts/model.lock.json (MIT).\n')
    # Metadata ships with the app; large weights/frontends ship only in tests or explicit download.
    shutil.copyfile(ROOT/'config/tts/model.lock.json', BUILD/'assets/tts-model-lock.json')
    test=BUILD/'test-assets/tts-test'; test.mkdir(parents=True,exist_ok=True)
    lock=json.loads((ROOT/'config/tts/model.lock.json').read_text())
    for name, item in lock['files'].items():
        path=BUILD/'model'/name
        if sha(path)!=item['sha256'] or path.stat().st_size!=item['size']: raise ValueError('Model changed')
        shutil.copyfile(path,test/name)
    outputs=[BUILD/'speech.aar']+[p for parent in ['assets','test-assets'] for p in (BUILD/parent).rglob('*') if p.is_file()]
    (BUILD/'manifest.json').write_text(json.dumps({'inputs':signature(),'outputs':{
        str(p.relative_to(BUILD)):{'size':p.stat().st_size,'sha256':sha(p)} for p in outputs}},indent=2)+'\n')
    verify()


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__); parser.add_argument('--verify',action='store_true')
    if parser.parse_args().verify: verify()
    else: build()
