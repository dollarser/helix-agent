"""On-demand packaging is independent of inference: keep runtime/licenses, never ship the weights."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('asr_download', ROOT / 'scripts/prepare-asr-download-assets.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class AsrDownloadAssetsTest(unittest.TestCase):
    def test_generated_main_assets_reject_model_files(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / 'encoder.int8.onnx').write_bytes(b'model')
            with self.assertRaises(ValueError):
                module.assert_no_weights(root)

    def test_apk_requires_descriptor_and_runtime_but_rejects_weights(self):
        with tempfile.TemporaryDirectory() as folder:
            apk = Path(folder) / 'candidate.apk'
            with zipfile.ZipFile(apk, 'w') as archive:
                archive.writestr('assets/asr-model-lock.json', module.LOCK.read_bytes())
                archive.writestr('lib/arm64-v8a/libsherpa-onnx-jni.so', b'runtime')
                archive.writestr('lib/arm64-v8a/libonnxruntime.so', b'runtime')
                archive.writestr('assets/asr-licenses/SOURCES.txt', b'sources')
            module.verify_apk(apk)
            with zipfile.ZipFile(apk, 'a') as archive:
                archive.writestr('assets/asr/encoder.int8.onnx', b'model')
            with self.assertRaises(ValueError):
                module.verify_apk(apk)

    def test_model_urls_are_pinned_and_match_the_qualified_size(self):
        lock = json.loads(module.LOCK.read_text())
        self.assertEqual(4, len(lock['files']))
        for row in lock['files'].values():
            self.assertIn('/resolve/' + lock['revision'] + '/', row['url'])
            self.assertEqual(64, len(row['sha256']))
        self.assertEqual(60142871, sum(row['size'] for row in lock['files'].values()))
