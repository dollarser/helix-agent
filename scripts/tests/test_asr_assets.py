"""Host-only archive/integrity boundary tests; no network or Android device access."""
import importlib.util
import io
import json
from pathlib import Path
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
spec = importlib.util.spec_from_file_location('asr_assets', SCRIPTS / 'build-asr-assets.py')
asr = importlib.util.module_from_spec(spec)
spec.loader.exec_module(asr)


class AsrAssetsTest(unittest.TestCase):
    def test_prepared_cache_is_relocatable_but_rejects_changed_inputs_and_corrupt_bytes(self):
        recipes = [(asr, 'BUILD', 'input_signature')]
        for filename, output, signature in [('build-tts-assets.py', 'BUILD', 'signature'),
                                             ('prepare-asr-download-assets.py', 'OUTPUT', 'inputs')]:
            spec = importlib.util.spec_from_file_location(filename, SCRIPTS / filename)
            module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(module)
            recipes.append((module, output, signature))
        for module, output, signature in recipes:
            with self.subTest(recipe=module.__name__), tempfile.TemporaryDirectory() as folder:
                root = Path(folder)
                payload = root / 'payload.bin'
                payload.write_bytes(b'verified')
                inputs = {'recipe': 'locked', 'lock': 'locked'}
                (root / 'manifest.json').write_text(json.dumps({'inputs': inputs, 'outputs': {
                    'payload.bin': {'size': payload.stat().st_size, 'sha256': asr.digest(payload)}}}))
                with patch.object(module, output, root), patch.object(module, signature, return_value=inputs):
                    module.verify()  # No native source tree, compiler, download or device required.
                    payload.write_bytes(b'corrupt!')
                    with self.assertRaises(ValueError):
                        module.verify()
                    payload.write_bytes(b'verified')
                    with patch.object(module, signature, return_value={'recipe': 'changed', 'lock': 'locked'}):
                        with self.assertRaises(ValueError):
                            module.verify()

    def test_rejects_archive_traversal(self):
        for name in ('../escape', '/absolute', 'root/../escape'):
            with self.assertRaises(ValueError):
                asr.safe_relative(name)

    def test_source_archive_rejects_unsafe_members_without_writing_outside_root(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            archive = root / 'source.tar.gz'
            with tarfile.open(archive, 'w:gz') as target:
                info = tarfile.TarInfo('source/../escape')
                info.size = 4
                target.addfile(info, io.BytesIO(b'bad!'))
            with self.assertRaises(ValueError):
                asr.unpack_source(archive, root / 'out')
            self.assertFalse((root / 'escape').exists())

    def test_source_restore_preserves_good_files_and_repairs_changed_input(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            archive = root / 'source.tar.gz'
            with tarfile.open(archive, 'w:gz') as target:
                info = tarfile.TarInfo('source/CMakeLists.txt')
                info.size = 4
                target.addfile(info, io.BytesIO(b'good'))
            output = root / 'out'
            asr.unpack_source(archive, output)
            modified = (output / 'CMakeLists.txt').stat().st_mtime_ns
            asr.unpack_source(archive, output)
            self.assertEqual(modified, (output / 'CMakeLists.txt').stat().st_mtime_ns)
            (output / 'CMakeLists.txt').write_bytes(b'bad')
            asr.unpack_source(archive, output)
            self.assertEqual(b'good', (output / 'CMakeLists.txt').read_bytes())


if __name__ == '__main__':
    unittest.main()
