"""The bundled VAD is a fixed, separately licensed asset; speech recognition stays on demand."""
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('vad_assets', ROOT / 'scripts/prepare-vad-assets.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class VadAssetsTest(unittest.TestCase):
    def test_reviewed_fixed_model_and_license(self):
        row = module.specification()
        self.assertEqual('MIT', row['license'])
        self.assertEqual('v5.0', row['revision'])
        self.assertEqual(2313101, row['size'])

    def test_model_and_apk_damage_are_rejected(self):
        row = module.specification()
        model = b'x' * 100_000
        license_bytes = module.LICENSE.read_bytes()
        fixture = dict(row, size=len(model), sha256=hashlib.sha256(model).hexdigest())
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            (folder / 'silero-v5.onnx').write_bytes(model)
            (folder / 'LICENSE-MIT.txt').write_bytes(license_bytes)
            (folder / 'model.lock.json').write_text(json.dumps(fixture))
            with patch.object(module, 'specification', return_value=fixture), patch.object(module, 'OUTPUT', folder):
                module.verify()
                (folder / 'silero-v5.onnx').write_bytes(b'y' * len(model))
                with self.assertRaises(ValueError):
                    module.verify()
                apk = folder / 'test.apk'
                with zipfile.ZipFile(apk, 'w') as archive:
                    archive.writestr('assets/voice-vad/silero-v5.onnx', model)
                    archive.writestr('assets/voice-vad/LICENSE-MIT.txt', b'bad license')
                with self.assertRaises(ValueError):
                    module.verify_apk(apk)

    def test_lock_rejects_oversized_model_or_changed_license(self):
        original = json.loads(module.LOCK.read_text())
        with tempfile.TemporaryDirectory() as directory:
            lock = Path(directory) / 'lock.json'
            with patch.object(module, 'LOCK', lock):
                for invalid in (dict(original, size=5_000_001), dict(original, licenseSha256='0' * 64)):
                    lock.write_text(json.dumps(invalid))
                    with self.assertRaises(ValueError):
                        module.specification()
