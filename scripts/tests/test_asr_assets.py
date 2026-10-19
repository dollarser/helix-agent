"""Host-only archive/integrity boundary tests; no network or Android device access."""
import importlib.util
import io
from pathlib import Path
import sys
import tarfile
import tempfile
import unittest

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
spec = importlib.util.spec_from_file_location('asr_assets', SCRIPTS / 'build-asr-assets.py')
asr = importlib.util.module_from_spec(spec)
spec.loader.exec_module(asr)


class AsrAssetsTest(unittest.TestCase):
    def test_rejects_archive_traversal(self):
        for name in ('../escape', '/absolute', 'root/../escape'):
            with self.assertRaises(ValueError):
                asr.safe_relative(name)

    def test_whitelist_never_extracts_unrequested_model_variants(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            archive = root / 'model.tar.bz2'
            with tarfile.open(archive, 'w:bz2') as target:
                for name, content in [('model/model.onnx', b'fp32'), ('model/model.int8.onnx', b'int8')]:
                    info = tarfile.TarInfo(name)
                    info.size = len(content)
                    target.addfile(info, io.BytesIO(content))
            asr.extract_model(archive, {'model.int8.onnx': root / 'out/model.onnx'})
            self.assertEqual(b'int8', (root / 'out/model.onnx').read_bytes())
            self.assertEqual(['model.onnx'], [p.name for p in (root / 'out').iterdir()])
            with self.assertRaises(ValueError):
                asr.extract_model(archive, {'missing.onnx': root / 'missing'})

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
