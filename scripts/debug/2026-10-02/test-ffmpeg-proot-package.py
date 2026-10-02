#!/usr/bin/env python3
"""Host-only regression checks for the actual pinned PRoot FFmpeg packaging input."""
from pathlib import Path
import importlib.util
import json
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
SPEC = importlib.util.spec_from_file_location('prepare_ffmpeg', ROOT / 'scripts/prepare-ffmpeg-proot.py')
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
CANDIDATE = ROOT / 'runtime/proot-app/vendor/ffmpeg-9.0.2-ready-av1-arm64-v8a.zip'


class PackageTests(unittest.TestCase):
    def test_exact_candidate_and_dependency_closure(self):
        entries, manifest = MODULE.validated_entries(CANDIDATE)
        native = {k for k in entries if k.startswith('jniLibs/')}
        self.assertEqual(8, len(native))
        self.assertEqual(10_080_568, manifest['runtime_bytes'])
        self.assertLess(sum(len(entries[k]) for k in native), 25_000_000)
        self.assertIn('assets/runtime/media/NOTICE.txt', entries)
        self.assertTrue(any('LICENSES/dav1d/' in k for k in entries))
        self.assertFalse(any(Path(k).suffix.lower() in {'.ttf', '.otf', '.ttc'} for k in entries))

    def test_repeated_extraction_has_identical_content(self):
        with tempfile.TemporaryDirectory() as folder:
            out = Path(folder) / 'payload'
            MODULE.prepare(CANDIDATE, out)
            first = {str(p.relative_to(out)): MODULE.digest(p.read_bytes()) for p in out.rglob('*') if p.is_file()}
            MODULE.prepare(CANDIDATE, out)
            second = {str(p.relative_to(out)): MODULE.digest(p.read_bytes()) for p in out.rglob('*') if p.is_file()}
            self.assertEqual(first, second)
            self.assertFalse(out.with_name('payload.previous').exists())

    def test_corrupt_candidate_preserves_previous_output(self):
        with tempfile.TemporaryDirectory() as folder:
            area = Path(folder)
            out = area / 'payload'
            out.mkdir()
            (out / 'sentinel').write_text('original')
            bad = area / 'bad.zip'
            data = bytearray(CANDIDATE.read_bytes())
            data[len(data) // 2] ^= 1
            bad.write_bytes(data)
            with self.assertRaises(ValueError):
                MODULE.prepare(bad, out)
            self.assertEqual('original', (out / 'sentinel').read_text())

    def test_symlink_candidate_cannot_substitute_file_identity(self):
        with tempfile.TemporaryDirectory() as folder:
            link = Path(folder) / 'alias.zip'
            link.symlink_to(CANDIDATE)
            with self.assertRaises(ValueError):
                MODULE.validated_entries(link)


if __name__ == '__main__':
    unittest.main(verbosity=2)
