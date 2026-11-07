"""Storage relocation must preserve native/schema identity without reversing dependencies."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
MODULE = ROOT / 'infrastructure/storage'


class StorageModuleBoundaryTest(unittest.TestCase):
    def test_room_is_an_infrastructure_module_with_neutral_dependencies(self):
        settings = (ROOT / 'settings.gradle.kts').read_text()
        build = (ROOT / 'build.gradle.kts').read_text()
        self.assertIn('\":infrastructure:storage\",', settings)
        self.assertNotIn('\":core:storage\",', settings)
        self.assertIn('\":infrastructure:storage\" to \"com.helix.core.storage\"', build)
        declared = re.search(r'\":infrastructure:storage\" to listOf\(([^)]*)\)', build)
        self.assertIsNotNone(declared)
        self.assertTrue(all(p.startswith(':core:') for p in re.findall(r'\"([^\"]+)\"', declared.group(1))))

    def test_jni_and_schema_keep_the_existing_runtime_identity(self):
        native = (MODULE / 'src/main/cpp/workspace_identity.c').read_text()
        bridge = (MODULE / 'src/main/kotlin/com/helix/core/storage/WorkspaceNativeIdentity.kt').read_text()
        self.assertIn('Java_com_helix_core_storage_WorkspaceNativeIdentity_read', native)
        self.assertIn('package com.helix.core.storage', bridge)
        self.assertIn('System.loadLibrary("workspace_identity")', bridge)
        self.assertTrue((MODULE / 'src/androidTest/assets/com.helix.core.storage.HelixDatabase/1.json').is_file())
        self.assertIn('infrastructure/storage/src/androidTest/assets', (ROOT / 'app/build.gradle.kts').read_text())

    def test_storage_does_not_import_application_or_presentation(self):
        for path in (MODULE / 'src/main/kotlin').rglob('*.kt'):
            with self.subTest(source=path.relative_to(ROOT)):
                source = path.read_text()
                for forbidden in ('com.helix.app', 'com.helix.feature', 'com.helix.ui', 'androidx.compose'):
                    self.assertNotIn(forbidden, source)


if __name__ == '__main__':
    unittest.main()
