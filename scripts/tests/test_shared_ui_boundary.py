"""Shared rendering owns its resources and cannot reach app/feature state."""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
SHARED = ROOT / "ui/shared/src"


class SharedUiBoundaryTest(unittest.TestCase):
    def test_shared_code_has_no_app_feature_or_storage_dependency(self):
        for path in SHARED.rglob("*.kt"):
            with self.subTest(path=path.relative_to(ROOT)):
                self.assertIsNone(re.search(
                    r"\bcom\.helix\.(?:app|feature|infrastructure|core\.storage)\.",
                    path.read_text(),
                ))

    def test_localized_resource_keys_have_one_owner(self):
        for locale in ["values", "values-en", "values-zh-rCN"]:
            shared = ET.parse(SHARED / "main/res" / locale / "strings.xml").getroot()
            keys = [item.attrib["name"] for item in shared]
            self.assertEqual(len(keys), len(set(keys)))
            if locale == "values":
                default = set(keys)
            self.assertEqual(default, set(keys))
            for owner in [ROOT / "app/src/main/res", ROOT / "feature/conversation/src/main/res"]:
                for path in (owner / locale).glob("*.xml"):
                    with self.subTest(path=path.relative_to(ROOT)):
                        existing = {item.attrib.get("name") for item in ET.parse(path).getroot()}
                        self.assertFalse(default & existing, sorted(default & existing))

    def test_drawables_have_one_owner(self):
        for path in (SHARED / "main/res/drawable").glob("*.xml"):
            for owner in [ROOT / "app/src/main/res", ROOT / "feature/conversation/src/main/res"]:
                with self.subTest(drawable=path.name, owner=owner.relative_to(ROOT)):
                    self.assertFalse((owner / "drawable" / path.name).exists())

    def test_rendering_assets_have_no_duplicate_app_copy(self):
        for name in ["math", "mermaid", "svg"]:
            self.assertTrue((SHARED / "main/assets" / name).is_dir())
            self.assertFalse((ROOT / "app/src/main/assets" / name).exists())
        for name in ["KaTeX-MIT", "Mermaid-MIT", "Mermaid-dependencies", "DOMPurify-LICENSE", "core-js-MIT"]:
            self.assertTrue((SHARED / "main/assets/licenses" / (name + ".txt")).is_file())
            self.assertFalse((ROOT / "app/src/main/assets/licenses" / (name + ".txt")).exists())
