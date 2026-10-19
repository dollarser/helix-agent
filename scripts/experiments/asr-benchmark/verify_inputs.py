"""Integrity boundary for opt-in benchmark inputs; no downloads or device effects."""
import hashlib
import json
from pathlib import Path

LOCK = Path(__file__).with_name('inputs.lock.json')


def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            value.update(chunk)
    return value.hexdigest()


def verify_inputs(root, models):
    lock = json.loads(LOCK.read_text())
    if digest(root / 'corpus.json') != lock['corpusSha256']:
        raise ValueError('Corpus differs from the frozen benchmark')
    corpus = json.loads((root / 'corpus.json').read_text())
    for item in corpus:
        path = root / item['file']
        if not path.resolve().is_relative_to(root.resolve()):
            raise ValueError('Audio path leaves benchmark root')
        if digest(path) != item['sha256'] or path.stat().st_size != item['samples'] * 2:
            raise ValueError('PCM differs: ' + item['id'])
    for model in models:
        expected = lock['models'][model]
        directory = root / 'models' / model
        actual = {str(p.relative_to(directory)) for p in directory.rglob('*') if p.is_file()}
        if actual != set(expected):
            raise ValueError('Missing/extra model files: ' + model)
        for name, entry in expected.items():
            path = directory / name
            if path.stat().st_size != entry['size'] or digest(path) != entry['sha256']:
                raise ValueError('Model differs: ' + model + '/' + name)
    return corpus
