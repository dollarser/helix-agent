"""Release identity and compressed payload gates must survive shrinking, not be bypassed."""
import hashlib
import importlib.util
import io
import json
import lzma
from pathlib import Path
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('optimized_apk_guard', ROOT / 'scripts/verify-integrated-runtime-apks.py')
GUARD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GUARD)


class OptimizedApkBoundaryTest(unittest.TestCase):
    def archive(self, payload=None, raw_duplicate=False, wrong_hash=False):
        raw = b'locked tar content' * 100
        component = {'id': 'alpine-rootfs', 'url': 'https://example.org/root.tar', 'size': len(raw),
                     'sha256': '0' * 64 if wrong_hash else hashlib.sha256(raw).hexdigest()}
        output = io.BytesIO()
        with zipfile.ZipFile(output, 'w') as archive:
            archive.writestr('assets/runtime/runtime-lock.json', json.dumps({'components': [component]}))
            archive.writestr('assets/runtime/rootfs/root.tar.xz',
                             payload if payload is not None else lzma.compress(raw))
            if raw_duplicate:
                archive.writestr('assets/runtime/rootfs/root.tar', raw)
        output.seek(0)
        return zipfile.ZipFile(output)

    def test_mapping_reconstructs_only_classes_actually_defined(self):
        mapping = 'com.helix.runtime.cli.Live -> a:\ncom.helix.removed.Gone -> b:\n'
        identities = GUARD.original_dex_identities({'a'}, mapping)
        self.assertIn(b'Lcom/helix/runtime/cli/Live;', identities)
        self.assertNotIn(b'Lcom/helix/removed/Gone;', identities)

    def test_mapping_does_not_hide_forbidden_original_namespace(self):
        mapping = 'com.helix.runtime.cli.Leaked -> x:\n'
        self.assertIn(b'Lcom/helix/runtime/cli/', GUARD.original_dex_identities({'x'}, mapping))

    def test_valid_single_encoded_payload_passes(self):
        with self.archive() as archive:
            GUARD.verify_rootfs_payload(archive)

    def test_wrong_hash_raw_duplicate_and_truncated_payload_fail(self):
        for options in ({'wrong_hash': True}, {'raw_duplicate': True}, {'payload': lzma.compress(b'bad')[:-3]}):
            with self.subTest(options=options), self.archive(**options) as archive:
                with self.assertRaises((RuntimeError, lzma.LZMAError)):
                    GUARD.verify_rootfs_payload(archive)

    def test_concatenated_streams_fail(self):
        encoded = lzma.compress(b'locked tar content' * 100)
        with self.archive(payload=encoded + encoded) as archive:
            with self.assertRaises(RuntimeError):
                GUARD.verify_rootfs_payload(archive)


if __name__ == '__main__':
    unittest.main()
