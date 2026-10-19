import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from verify_inputs import verify_inputs


class InputIntegrityTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        (self.root / 'models/a').mkdir(parents=True)
        self.model = self.root / 'models/a/model.onnx'
        self.model.write_bytes(b'model')
        self.pcm = self.root / 'sample.pcm'
        self.pcm.write_bytes(b'\0\0')
        self.rows = [dict(id='one', file='sample.pcm', samples=1,
                          sha256=hashlib.sha256(self.pcm.read_bytes()).hexdigest())]
        self.lock = self.root / 'lock.json'
        self.freeze()
        self.override = patch('verify_inputs.LOCK', self.lock)
        self.override.start()

    def tearDown(self):
        self.override.stop()
        self.temp.cleanup()

    def freeze(self):
        corpus = json.dumps(self.rows).encode()
        (self.root / 'corpus.json').write_bytes(corpus)
        self.lock.write_text(json.dumps(dict(corpusSha256=hashlib.sha256(corpus).hexdigest(),
            models={'a': {'model.onnx': dict(size=5, sha256=hashlib.sha256(b'model').hexdigest())}})))

    def test_valid_frozen_inputs(self):
        self.assertEqual(verify_inputs(self.root, ['a']), self.rows)

    def test_changed_model_rejected(self):
        self.model.write_bytes(b'other')
        with self.assertRaisesRegex(ValueError, 'Model differs'):
            verify_inputs(self.root, ['a'])

    def test_extra_model_file_rejected(self):
        (self.model.parent / 'unexpected').write_bytes(b'x')
        with self.assertRaisesRegex(ValueError, 'extra model'):
            verify_inputs(self.root, ['a'])

    def test_changed_audio_rejected(self):
        self.pcm.write_bytes(b'\1\0')
        with self.assertRaisesRegex(ValueError, 'PCM differs'):
            verify_inputs(self.root, ['a'])

    def test_path_escape_rejected(self):
        self.rows[0]['file'] = '../outside.pcm'
        self.freeze()
        with self.assertRaisesRegex(ValueError, 'leaves benchmark root'):
            verify_inputs(self.root, ['a'])


if __name__ == '__main__': unittest.main()
