#!/usr/bin/env python3
"""Build the pinned ASR-only Android JNI and bundled model; never invoke a device."""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import subprocess
import tarfile
import zipfile

from ci.fetch_locked_asset import fetch, matches

ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / 'build/asr'
LOCK = ROOT / 'config/asr/dependencies.lock.json'
ABIS = ('arm64-v8a', 'x86_64')


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def safe_relative(name):
    path = PurePosixPath(name)
    if path.is_absolute() or '..' in path.parts or not path.parts:
        raise ValueError('Unsafe archive member: ' + name)
    return Path(*path.parts)


def unpack_source(archive, destination):
    with tarfile.open(archive) as source:
        for member in source:
            relative = safe_relative(member.name)
            if member.isfile():
                output = destination / Path(*relative.parts[1:])
                output.parent.mkdir(parents=True, exist_ok=True)
                content = source.extractfile(member).read()
                if not output.is_file() or output.read_bytes() != content:
                    output.write_bytes(content)


def extract_model(archive, wanted):
    remaining = dict(wanted)
    # Stream once: the upstream archive contains several unused precision/chunk variants.
    with tarfile.open(archive, mode='r|bz2') as source:
        for member in source:
            relative = safe_relative(member.name)
            key = '/'.join(relative.parts[1:])
            if key in remaining:
                if not member.isfile():
                    raise ValueError('Expected regular model file: ' + key)
                output = remaining.pop(key)
                output.parent.mkdir(parents=True, exist_ok=True)
                with source.extractfile(member) as stream, output.open('wb') as target:
                    shutil.copyfileobj(stream, target)
    if remaining:
        raise ValueError('Missing model files: ' + ', '.join(remaining))


def input_signature():
    return {'lock': digest(LOCK), 'recipe': digest(Path(__file__)),
            'notices': {p.name: digest(p) for p in sorted((ROOT / 'config/asr/notices').glob('*.txt'))}}


def verify():
    manifest = json.loads((BUILD / 'manifest.json').read_text())
    if manifest['inputs'] != input_signature():
        raise ValueError('ASR input lock/recipe changed; run python3 scripts/build-asr-assets.py')
    for name, value in manifest['outputs'].items():
        if not matches(BUILD / safe_relative(name), value['sha256'], value['size']):
            raise ValueError('Missing or modified ASR output: ' + name)
    print('ASR-only JNI/model/attribution integrity verified (host-only)')


def package_runtime():
    output = BUILD / 'asr-only.aar'
    with zipfile.ZipFile(BUILD / 'sherpa.aar') as original, zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as target:
        for name in ('AndroidManifest.xml', 'classes.jar', 'R.txt', 'META-INF/com/android/build/gradle/aar-metadata.properties'):
            target.writestr(name, original.read(name))
        for abi in ABIS:
            library = BUILD / 'native' / abi / 'lib/libsherpa-onnx-jni.so'
            target.write(library, f'jni/{abi}/libsherpa-onnx-jni.so')
            target.write(BUILD / 'ort/jni' / abi / 'libonnxruntime.so', f'jni/{abi}/libonnxruntime.so')
        target.writestr('proguard.txt', '-keep class com.k2fsa.sherpa.onnx.** { *; }\n')


def attribution():
    output = BUILD / 'assets/asr-licenses'
    output.mkdir(parents=True, exist_ok=True)
    deps = BUILD / 'native/arm64-v8a/_deps'
    files = {'sherpa-Apache-2.0.txt': BUILD / 'source/LICENSE'}
    for dependency, names in {
        'kaldi_native_fbank': ['LICENSE'], 'kaldi_decoder': ['LICENSE'], 'kaldifst': ['LICENSE'],
        'openfst': ['COPYING', 'AUTHORS'], 'simple-sentencepiece': ['LICENSE'],
        'kissfft': ['COPYING', 'LICENSES/BSD-3-Clause'], 'json': ['LICENSE.MIT'],
        'eigen': ['COPYING.MPL2', 'COPYING.BSD', 'COPYING.MINPACK', 'COPYING.APACHE', 'COPYING.README'],
    }.items():
        for name in names:
            files[dependency + '-' + name.replace('/', '-') + '.txt'] = deps / (dependency + '-src') / name
    # The ORT distribution embeds its corresponding notices in META-INF.
    with zipfile.ZipFile(BUILD / 'ort.zip') as archive:
        for name in archive.namelist():
            if 'license' in name.lower() or 'notice' in name.lower():
                if not name.endswith('/'):
                    (output / ('onnxruntime-' + Path(name).name)).write_bytes(archive.read(name))
    for name, source in files.items():
        shutil.copyfile(source, output / name)
    (output / 'SOURCES.txt').write_text(
        'ASR only; TTS/eSpeak/speaker diarization/WebSocket/binaries disabled.\n'
        'sherpa-onnx v1.13.8, Apache-2.0; ONNX Runtime 1.28.2, MIT.\n'
        'Native sources and transitive source URLs/hashes: https://github.com/k2-fsa/sherpa-onnx/tree/v1.13.8/cmake\n'
        'Unmodified Eigen 5.0.1 MPL-2.0 source: https://gitlab.com/libeigen/eigen/-/archive/5.0.1/eigen-5.0.1.tar.gz\n'
        'Model: https://huggingface.co/csukuangfj/k2fsa-zipformer-bilingual-zh-en-t (Apache-2.0).\n'
        'Converted model: sherpa-onnx-streaming-zipformer-small-bilingual-zh-en-2023-02-16.\n'
        'No Operit implementation is copied.\n', encoding='utf-8')
    for notice in (ROOT / 'config/asr/notices').glob('*.txt'):
        shutil.copyfile(notice, output / notice.name)


def build():
    BUILD.mkdir(parents=True, exist_ok=True)
    lock = json.loads(LOCK.read_text())
    for name, value in lock.items():
        path = BUILD / name
        if not matches(path, value['sha256'], value['size']):
            fetch(value['url'], path, value['sha256'], value['size'])
    unpack_source(BUILD / 'source.tar.gz', BUILD / 'source')
    if not (BUILD / 'ort/headers/onnxruntime_c_api.h').exists():
        with zipfile.ZipFile(BUILD / 'ort.zip') as source:
            for name in source.namelist():
                relative = safe_relative(name)
                if not name.endswith('/'):
                    output = BUILD / 'ort' / relative
                    output.parent.mkdir(parents=True, exist_ok=True)
                    output.write_bytes(source.read(name))
    sdk = Path(os.environ.get('ANDROID_HOME', os.environ.get('ANDROID_SDK_ROOT', Path.home() / 'Library/Android/sdk')))
    cmake = sdk / 'cmake/3.31.6/bin/cmake'
    ndk = sdk / 'ndk/28.2.13676358'
    for abi in ABIS:
        environment = dict(os.environ, SHERPA_ONNXRUNTIME_LIB_DIR=str(BUILD / 'ort/jni' / abi),
                           SHERPA_ONNXRUNTIME_INCLUDE_DIR=str(BUILD / 'ort/headers'))
        args = [str(cmake), '-S', str(BUILD / 'source'), '-B', str(BUILD / 'native' / abi), '-G', 'Ninja',
                '-DCMAKE_MAKE_PROGRAM=' + str(sdk / 'cmake/3.31.6/bin/ninja'),
                '-DCMAKE_TOOLCHAIN_FILE=' + str(ndk / 'build/cmake/android.toolchain.cmake'),
                '-DANDROID_ABI=' + abi, '-DANDROID_PLATFORM=android-29', '-DANDROID_STL=c++_static',
                '-DCMAKE_BUILD_TYPE=Release', '-DBUILD_SHARED_LIBS=ON', '-DCMAKE_CXX_FLAGS=-DEIGEN_MPL2_ONLY']
        flags = {'TTS': 'OFF', 'SPEAKER_DIARIZATION': 'OFF', 'BINARY': 'OFF', 'C_API': 'OFF',
                 'PORTAUDIO': 'OFF', 'WEBSOCKET': 'OFF', 'JNI': 'ON', 'PYTHON': 'OFF', 'TESTS': 'OFF', 'CHECK': 'OFF'}
        args += [f'-DSHERPA_ONNX_ENABLE_{key}={value}' for key, value in flags.items()]
        subprocess.run(args, env=environment, check=True)
        subprocess.run([str(cmake), '--build', str(BUILD / 'native' / abi), '--target', 'sherpa-onnx-jni',
                        '-j', str(min(os.cpu_count() or 2, 6))], env=environment, check=True)
    package_runtime()
    for generated in ('assets', 'test-assets'):
        if (BUILD / generated).exists():
            shutil.rmtree(BUILD / generated)
    extract_model(BUILD / 'model.tar.bz2', {
        'encoder-epoch-99-avg-1.int8.onnx': BUILD / 'assets/asr/encoder.int8.onnx',
        'decoder-epoch-99-avg-1.onnx': BUILD / 'assets/asr/decoder.onnx',
        'joiner-epoch-99-avg-1.int8.onnx': BUILD / 'assets/asr/joiner.int8.onnx',
        'tokens.txt': BUILD / 'assets/asr/tokens.txt',
        'test_wavs/0.wav': BUILD / 'test-assets/asr-test/0.wav',
    })
    extract_model(BUILD / 'ctc-model.tar.bz2', {
        'model.int8.onnx': BUILD / 'test-assets/asr-candidate/model.int8.onnx',
        'tokens.txt': BUILD / 'test-assets/asr-candidate/tokens.txt',
        'test_wavs/0.wav': BUILD / 'test-assets/asr-candidate/test_wavs/0.wav',
        'test_wavs/1.wav': BUILD / 'test-assets/asr-candidate/test_wavs/1.wav',
    })
    attribution()
    outputs = [BUILD / 'asr-only.aar'] + [p for folder in ('assets', 'test-assets') for p in (BUILD / folder).rglob('*') if p.is_file()]
    manifest = {'inputs': input_signature(), 'outputs': {str(p.relative_to(BUILD)): {'sha256': digest(p), 'size': p.stat().st_size} for p in outputs}}
    (BUILD / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    verify()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--verify', action='store_true', help='Verify prepared inputs without network/build/device access')
    args = parser.parse_args()
    if args.verify:
        verify()
    else:
        build()


if __name__ == '__main__':
    main()
