import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("preview_assets", Path(__file__).parents[1] / "verify-preview-assets.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class PreviewAssetsTest(unittest.TestCase):
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

        diagram = self.root / "app/src/main/assets/mermaid/mermaid.tiny.js"
        diagram.parent.mkdir(parents=True)
        diagram.write_bytes(b"synthetic diagram")
        notice = self.root / "app/src/main/assets/licenses/Mermaid-dependencies.txt"
        notice.write_bytes(b"synthetic notices")
        diagram_lock = self.root / "config/mermaid/mermaid.lock.json"
        diagram_lock.parent.mkdir(parents=True)
        diagram_lock.write_text(json.dumps({"files": {
            p.relative_to(self.root).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in [diagram, notice]}}))

        svg = self.root / "app/src/main/assets/svg/purify.min.js"
        svg.parent.mkdir(parents=True)
        svg.write_bytes(b"synthetic sanitizer")
        (svg.parent / "sanitize.js").write_bytes(b"owned policy")
        svg_lock = self.root / "config/svg/dompurify.lock.json"
        svg_lock.parent.mkdir(parents=True)
        svg_lock.write_text(json.dumps({"files": {
            svg.relative_to(self.root).as_posix(): hashlib.sha256(svg.read_bytes()).hexdigest()}}))

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

    def test_diagram_code_notices_and_inventory_are_checked(self):
        module.verify(self.root)
        diagram = self.root / "app/src/main/assets/mermaid/mermaid.tiny.js"
        extra = diagram.parent / "unreviewed.js"
        extra.write_bytes(b"unreviewed")
        with self.assertRaisesRegex(ValueError, "Diagram.*inventory"):
            module.verify(self.root)
        extra.unlink()
        original = diagram.read_bytes()
        diagram.write_bytes(b"damaged")
        with self.assertRaisesRegex(ValueError, "Diagram.*hash mismatch"):
            module.verify(self.root)
        diagram.write_bytes(original)
        notice = self.root / "app/src/main/assets/licenses/Mermaid-dependencies.txt"
        notice.write_bytes(b"missing notice")
        with self.assertRaisesRegex(ValueError, "license hash mismatch"):
            module.verify(self.root)

    def test_svg_third_party_sanitizer_and_owned_inventory_are_checked(self):
        module.verify(self.root)
        svg = self.root / "app/src/main/assets/svg/purify.min.js"
        original = svg.read_bytes()
        svg.write_bytes(b"tampered")
        with self.assertRaisesRegex(ValueError, "SVG.*hash mismatch"):
            module.verify(self.root)
        svg.write_bytes(original)
        (svg.parent / "remote.js").write_bytes(b"unreviewed")
        with self.assertRaisesRegex(ValueError, "SVG.*inventory"):
            module.verify(self.root)
