import hashlib
import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('fetch', Path(__file__).resolve().parents[1] / 'ci/fetch_locked_asset.py')
fetch = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fetch)


class AssetCacheTest(unittest.TestCase):
    def test_verified_hit_needs_no_network(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            data = b'locked fixture'
            sha = hashlib.sha256(data).hexdigest()
            (root / sha).write_bytes(data)
            with patch.object(fetch.subprocess, 'run') as network:
                fetch.fetch('https://fixture.invalid/asset', root / 'nested/output', sha, len(data), root)
            network.assert_not_called()
            self.assertEqual(data, (root / 'nested/output').read_bytes())

    def test_corrupt_hit_is_replaced_only_by_verified_download(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            data = b'correct'
            sha = hashlib.sha256(data).hexdigest()
            (root / sha).write_bytes(b'corrupt')
            def download(command, **_):
                Path(command[command.index('--output') + 1]).write_bytes(data)
            with patch.object(fetch.subprocess, 'run', side_effect=download):
                fetch.fetch('https://fixture.invalid/asset', root / 'output', sha, len(data), root)
            self.assertEqual(data, (root / sha).read_bytes())
            self.assertEqual(data, (root / 'output').read_bytes())

    def test_bad_download_or_network_failure_never_publishes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            sha = hashlib.sha256(b'correct').hexdigest()
            def corrupt(command, **_):
                Path(command[command.index('--output') + 1]).write_bytes(b'bad')
            for failure in [corrupt, subprocess.CalledProcessError(1, 'curl')]:
                with patch.object(fetch.subprocess, 'run', side_effect=failure):
                    with self.assertRaises((ValueError, subprocess.CalledProcessError)):
                        fetch.fetch('https://fixture.invalid/asset', root / 'output', sha, 7, root / 'cache')
                self.assertFalse((root / 'output').exists())
                self.assertFalse((root / 'cache' / sha).exists())

    def test_invalid_lock_and_http_are_rejected(self):
        for url, sha, size in [('http://fixture.invalid', 'a' * 64, 1),
                               ('https://fixture.invalid', '../bad', 1),
                               ('https://fixture.invalid', 'a' * 64, 0)]:
            with self.assertRaises(ValueError):
                fetch.fetch(url, Path('unused'), sha, size)
