#!/usr/bin/env python3
"""Freeze first 20 FLEURS zh test clips + deterministic robustness probes."""
import argparse
import hashlib
import json
from pathlib import Path
import numpy as np
import soundfile as sf

R = Path('build/asr/benchmark')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--from-parquet', action='store_true')
    args = parser.parse_args()
    if args.from_parquet:
        from verify_inputs import digest, LOCK
        import pyarrow.parquet as pq
        archive = R / 'fleurs-zh.parquet'
        expected = json.loads(LOCK.read_text())['fleurs']
        if archive.stat().st_size != expected['size'] or digest(archive) != expected['sha256']:
            raise ValueError('FLEURS Parquet differs from official LFS identity')
        table = pq.read_table(archive, columns=['id', 'num_samples', 'path', 'audio',
                             'transcription', 'raw_transcription', 'gender']).slice(0, 20)
        recovered = table.to_pylist()
        folder = R / 'fleurs-first20'
        folder.mkdir(exist_ok=True)
        for index, row in enumerate(recovered):
            path = folder / f'{index:02}.wav'
            content = row.pop('audio')['bytes']
            if path.exists() and path.read_bytes() != content:
                raise ValueError('Existing sample differs from full Arrow decode: ' + str(index))
            path.write_bytes(content)
        (folder / 'rows.json').write_text(json.dumps(recovered, ensure_ascii=False, indent=2))
        print('Full official Parquet hash and all 20 existing WAV identities verified')
    rows = json.loads((R / 'fleurs-first20/rows.json').read_text())
    audio_dir = R / 'audio'
    audio_dir.mkdir(exist_ok=True)
    corpus = []
    sources = []
    def write(name, group, reference, data, source):
        path = audio_dir / (name + '.pcm')
        pcm = np.clip(np.rint(data * 32768), -32768, 32767).astype('<i2')
        path.write_bytes(pcm.tobytes())
        corpus.append(dict(id=name, group=group, reference=reference, file='audio/' + path.name,
                           samples=len(pcm), source=source, sha256=hashlib.sha256(path.read_bytes()).hexdigest()))
    clean = []
    for index, row in enumerate(rows):
        path = R / 'fleurs-first20' / f'{index:02}.wav'
        data, sample_rate = sf.read(path, dtype='float32')
        assert sample_rate == 16000 and data.ndim == 1
        assert len(data) == row['num_samples'], (index, len(data), row['num_samples'])
        reference = row['raw_transcription']
        name = f'fleurs-zh-{index:02}'
        source = dict(dataset='google/fleurs', config='cmn_hans_cn', split='test', row=index,
                      id=row['id'], originalPath=row['path'], license='CC-BY-4.0')
        write(name, 'zh', reference, data, source)
        clean.append((data, reference, source))
        sources.append(dict(file=str(path.relative_to(R)), sha256=hashlib.sha256(path.read_bytes()).hexdigest()))
    rng = np.random.default_rng(20261019)
    for index, (data, reference, source) in enumerate(clean[:5]):
        noise = rng.standard_normal(len(data)).astype(np.float32)
        noise *= np.sqrt(np.mean(data.astype(np.float64)**2) / (10 * np.mean(noise.astype(np.float64)**2)))
        write(f'noise10db-{index:02}', 'noise10db', reference, data + noise, source)
    english, sample_rate = sf.read(R / 'downloads/jfk.wav', dtype='float32')
    assert sample_rate == 16000 and english.ndim == 1
    english_text = 'And so my fellow Americans ask not what your country can do for you ask what you can do for your country'
    source = dict(url='https://github.com/ggml-org/whisper.cpp/blob/master/samples/jfk.wav', description='Public JFK speech excerpt')
    write('jfk', 'english', english_text, english, source)
    for index, (data, reference, zh_source) in enumerate(clean[:3]):
        # Constructed code switching is explicitly distinguished from natural mixed speech.
        combined = np.concatenate([data, np.zeros(8000, dtype=np.float32), english])
        write(f'constructed-mixed-{index:02}', 'mixed', reference + ' ' + english_text,
              combined, dict(constructed=True, chinese=zh_source, english=source))
    write('silence-zero', 'silence', '', np.zeros(5 * 16000, dtype=np.float32), dict(synthetic=True))
    write('silence-low-noise', 'silence', '', rng.normal(0, .0001, 5 * 16000).astype(np.float32), dict(synthetic=True))
    (R / 'corpus.json').write_text(json.dumps(corpus, ensure_ascii=False, indent=2) + '\n')
    (R / 'corpus-sources.json').write_text(json.dumps(sources, indent=2) + '\n')
    print(len(corpus), 'clips;', sum(x['samples'] for x in corpus)/16000, 'audio seconds; corpus sha256=', hashlib.sha256((R/'corpus.json').read_bytes()).hexdigest())


if __name__ == '__main__': main()
