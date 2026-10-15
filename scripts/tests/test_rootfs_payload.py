"""Host-only tests for the encoded RootFS build input; no guest execution."""
import hashlib
import importlib.util
import json
import lzma
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("rootfs_payload", ROOT / "scripts/prepare-rootfs-payload.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class RootfsPayloadTests(unittest.TestCase):
    def fixture(self, root):
        assets = root / "assets"
        (assets / "rootfs").mkdir(parents=True)
        raw = b"locked content" * 100
        (assets / "rootfs/test.tar").write_bytes(raw)
        (assets / "runtime-lock.json").write_text(json.dumps({"components": [{
            "id": "alpine-rootfs", "url": "https://example.org/test.tar", "size": len(raw),
            "sha256": hashlib.sha256(raw).hexdigest(),
        }]}))
        return assets, raw

    def test_deterministic_single_xz_stream_preserves_locked_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            assets, raw = self.fixture(root)
            target = MODULE.prepare(assets, root / "output")
            first = target.read_bytes()
            MODULE.prepare(assets, root / "output")
            self.assertEqual(first, target.read_bytes())
            decoder = lzma.LZMADecompressor(memlimit=16 * 1024 * 1024)
            self.assertEqual(raw, decoder.decompress(first))
            self.assertTrue(decoder.eof)
            self.assertEqual(b"", decoder.unused_data)

    def test_bad_hash_preserves_previous_payload_and_cleans_temporary_file(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            assets, raw = self.fixture(root)
            target = MODULE.prepare(assets, root / "output")
            previous = target.read_bytes()
            (assets / "rootfs/test.tar").write_bytes(b"x" * len(raw))
            with self.assertRaises(ValueError):
                MODULE.prepare(assets, root / "output")
            self.assertEqual(previous, target.read_bytes())
            self.assertEqual([target], list(target.parent.iterdir()))

    def test_size_mismatch_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            assets, _ = self.fixture(root)
            (assets / "rootfs/test.tar").write_bytes(b"wrong")
            with self.assertRaises(ValueError):
                MODULE.prepare(assets, root / "output")
            self.assertFalse((root / "output").exists())


if __name__ == "__main__":
    unittest.main()
