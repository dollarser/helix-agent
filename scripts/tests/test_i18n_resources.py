"""Feature XML files must receive the same translation gate as strings.xml."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class I18nResourceTest(unittest.TestCase):
    def run_gate(self, files):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            script = root / "scripts/check-i18n.sh"
            script.parent.mkdir()
            shutil.copyfile(Path(__file__).resolve().parents[1] / "check-i18n.sh", script)
            chat = root / "app/src/main/kotlin/com/helix/app/chat/ChatService.kt"
            chat.parent.mkdir(parents=True)
            chat.write_text("// Synthetic empty chat UI fixture\n")
            for name, body in files.items():
                path = root / "app/src/main/res" / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("<resources>" + body + "</resources>")
            return subprocess.run(["bash", str(script)], capture_output=True, text=True, timeout=20)

    @staticmethod
    def base():
        return {f"{locale}/strings.xml": '<string name="app_name">Synthetic</string>'
                for locale in ["values", "values-en", "values-zh-rCN"]}

    def test_feature_files_are_counted_across_different_groupings(self):
        files = self.base()
        for locale, name in [("values", "feature.xml"), ("values-en", "english.xml"),
                             ("values-zh-rCN", "chinese.xml")]:
            files[f"{locale}/{name}"] = '<string name="feature_title">Synthetic</string>'
        result = self.run_gate(files)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("2 resource keys in parity", result.stdout)

    def test_missing_feature_translation_fails(self):
        files = self.base()
        files["values/feature.xml"] = '<string name="feature_title">Synthetic</string>'
        files["values-en/feature.xml"] = '<string name="feature_title">Synthetic</string>'
        result = self.run_gate(files)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("feature_title", result.stderr)
        self.assertIn("values-zh-rCN", result.stderr)

    def test_non_translatable_constant_is_not_a_missing_translation(self):
        files = self.base()
        files["values/constants.xml"] = '<string name="protocol" translatable="false">synthetic-v1</string>'
        result = self.run_gate(files)
        self.assertEqual(0, result.returncode, result.stderr)

    def test_duplicate_key_across_files_fails(self):
        files = self.base()
        files["values/feature.xml"] = '<string name="app_name">Duplicate</string>'
        result = self.run_gate(files)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("duplicate resource app_name", result.stderr)


if __name__ == "__main__":
    unittest.main()
