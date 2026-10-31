import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("formula_assets", Path(__file__).parents[1] / "verify-formula-assets.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class FormulaAssetsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.asset = self.root / "app/src/main/assets/math/katex.min.js"
        self.license = self.root / "app/src/main/assets/licenses/KaTeX-MIT.txt"
        self.asset.parent.mkdir(parents=True)
        self.license.parent.mkdir(parents=True)
        self.asset.write_bytes(b"synthetic renderer")
        self.license.write_bytes(b"synthetic license")
        lock = self.root / "config/math/katex.lock.json"
        lock.parent.mkdir(parents=True)
        lock.write_text(json.dumps({"files": {"katex.min.js": hashlib.sha256(self.asset.read_bytes()).hexdigest()},
                                   "licenseSha256": hashlib.sha256(self.license.read_bytes()).hexdigest()}))

    def test_integrity_and_license_are_checked(self):
        module.verify(self.root)
        self.asset.write_bytes(b"damaged renderer")
        with self.assertRaisesRegex(ValueError, "hash mismatch"):
            module.verify(self.root)

    def test_unreviewed_missing_assets_and_changed_license_fail(self):
        extra = self.asset.parent / "extra.js"
        extra.write_bytes(b"unreviewed")
        with self.assertRaisesRegex(ValueError, "inventory"):
            module.verify(self.root)
        extra.unlink()
        self.license.write_bytes(b"changed")
        with self.assertRaisesRegex(ValueError, "license"):
            module.verify(self.root)
        self.asset.unlink()
        with self.assertRaisesRegex(ValueError, "inventory"):
            module.verify(self.root)
