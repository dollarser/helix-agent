"""Only the approved declarations may bypass the generic credential pattern."""
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from public_oauth import sanitize


class PublicOAuthTest(unittest.TestCase):
    path = 'runtime/cli-app/build.gradle.kts'

    def test_exact_declarations_only(self):
        source = (ROOT / self.path).read_bytes()
        declarations = [line for line in source.splitlines() if line.startswith(b'val upstreamAntigravityClient')]
        self.assertEqual(2, len(declarations))
        for line in declarations:
            self.assertNotEqual(line, sanitize(self.path, line))
            self.assertEqual(line, sanitize('other.kt', line))
            modified = line[:-1] + b'x"'
            self.assertEqual(modified, sanitize(self.path, modified))
            renamed = line.replace(b'upstreamAntigravityClient', b'otherClient')
            self.assertEqual(renamed, sanitize(self.path, renamed))


if __name__ == '__main__':
    unittest.main()
