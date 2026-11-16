"""Current acceptance runners must not depend on retired database migration fixtures."""
import ast
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
RUNNERS = ('accept-hxa-129-connectors.py', 'accept-conversation-interaction.py')


class CurrentStorageAcceptanceTest(unittest.TestCase):
    def test_declared_acceptance_classes_exist(self):
        for runner in RUNNERS:
            tree = ast.parse((ROOT / 'scripts' / runner).read_text())
            classes = set()
            for node in ast.walk(tree):
                if isinstance(node, ast.Constant) and isinstance(node.value, str):
                    classes.update(re.findall(r'com\.helix\.[A-Za-z0-9_.]+Test\b', node.value))
            self.assertTrue(classes, runner)
            for name in classes:
                module = 'infrastructure/storage' if name.startswith('com.helix.core.storage.') else 'app'
                source = ROOT / module / 'src/androidTest/kotlin' / (name.replace('.', '/') + '.kt')
                self.assertTrue(source.is_file(), f'{runner}: {name}')
                self.assertRegex(source.read_text(), r'\bclass\s+' + name.rsplit('.', 1)[1] + r'\b')

    def test_storage_phases_keep_current_reset_boundaries(self):
        for runner in RUNNERS:
            source = (ROOT / 'scripts' / runner).read_text()
            self.assertIn('com.helix.core.storage.FreshSchemaDeviceTest', source)
            self.assertIn('com.helix.core.storage.PreviewDatabaseGateDeviceTest', source)
            self.assertNotIn('MigrationDeviceTest', source)
            self.assertNotIn('RoomMigrationFixtureTest', source)


if __name__ == '__main__':
    unittest.main()
