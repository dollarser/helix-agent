"""The narrow FFmpeg correction must reject changed sources, patches and targets."""
import difflib
import hashlib
import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest

HERE = Path(__file__).resolve().parents[1] / 'experiments/ffmpeg/ffmpeg-ready'
sys.path.insert(0, str(HERE))
try:
    SPEC = importlib.util.spec_from_file_location('ffmpeg_source_patches', HERE / 'source_patches.py')
    MODULE = importlib.util.module_from_spec(SPEC)
    SPEC.loader.exec_module(MODULE)
finally:
    sys.path.remove(str(HERE))


def sha(data):
    return hashlib.sha256(data).hexdigest()


class SourcePatchTests(unittest.TestCase):
    def fixture(self, root):
        target = 'libavcodec/mediacodecenc.c'
        before, after = b'int original;\n', b'int corrected;\n'
        source = root / 'source'
        file = source / target
        file.parent.mkdir(parents=True)
        file.write_bytes(before)
        patch = root / 'correction.patch'
        patch.write_text(''.join(difflib.unified_diff(before.decode().splitlines(True), after.decode().splitlines(True),
                                                    fromfile='a/' + target, tofile='b/' + target)))
        return source, patch, {'target': target, 'sha256': sha(patch.read_bytes()),
                               'before_sha256': sha(before), 'after_sha256': sha(after)}

    def test_exact_patch_changes_only_reviewed_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            source, patch, record = self.fixture(Path(tmp))
            MODULE.apply_patch(source, patch, record)
            self.assertEqual(b'int corrected;\n', (source / record['target']).read_bytes())

    def test_modified_source_is_rejected_without_writing(self):
        with tempfile.TemporaryDirectory() as tmp:
            source, patch, record = self.fixture(Path(tmp))
            file = source / record['target']
            file.write_bytes(b'int unrelated;\n')
            with self.assertRaisesRegex(RuntimeError, 'another upstream'):
                MODULE.apply_patch(source, patch, record)
            self.assertEqual(b'int unrelated;\n', file.read_bytes())

    def test_modified_patch_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            source, patch, record = self.fixture(Path(tmp))
            patch.write_text(patch.read_text() + '\n')
            with self.assertRaisesRegex(RuntimeError, 'patch SHA'):
                MODULE.apply_patch(source, patch, record)

    def test_unapproved_path_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            source, patch, record = self.fixture(Path(tmp))
            patch.write_text(patch.read_text().replace('b/libavcodec/mediacodecenc.c', 'b/../../outside'))
            record['sha256'] = sha(patch.read_bytes())
            with self.assertRaisesRegex(RuntimeError, 'unapproved path'):
                MODULE.apply_patch(source, patch, record)

    def test_wrong_output_digest_cannot_be_certified(self):
        with tempfile.TemporaryDirectory() as tmp:
            source, patch, record = self.fixture(Path(tmp))
            record['after_sha256'] = '0' * 64
            with self.assertRaisesRegex(RuntimeError, 'output SHA'):
                MODULE.apply_patch(source, patch, record)

    def test_packaged_patch_is_pinned_and_removes_encoder_preflight(self):
        record = MODULE.RECIPE['patches']['mediacodec-no-encoder-flush.patch']
        patch = HERE / 'mediacodec-no-encoder-flush.patch'
        self.assertEqual(record['sha256'], sha(patch.read_bytes()))
        self.assertIn('-        ff_AMediaCodec_flush(s->codec);', patch.read_text())
        self.assertIn('+ * Keep synchronous and asynchronous codecs on the same real-input lifecycle.', patch.read_text())


if __name__ == '__main__':
    unittest.main()
