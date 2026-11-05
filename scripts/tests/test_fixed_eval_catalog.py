"""Host-only contracts for the fixed evaluation catalog; no model or device calls."""
import hashlib
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2] / 'evals' / 'm10'


def rows(path):
    return [line.split('\t') for line in path.read_text(encoding='utf-8').splitlines()[1:]
            if line.strip()]


class FixedEvalCatalogTest(unittest.TestCase):
    def test_catalog_has_required_fixed_coverage_and_unique_ids(self):
        catalog = rows(ROOT / 'fixed-evals.tsv')
        self.assertGreaterEqual(len(catalog), 40)
        for row in catalog:
            self.assertEqual(len(row), 6)
            self.assertTrue(all(field.strip() for field in row))
            self.assertNotIn('\n', row[4])
        self.assertEqual(len(catalog), len({row[0] for row in catalog}))
        self.assertEqual(
            {row[1] for row in catalog},
            {'chat', 'plan', 'goal', 'provider', 'file', 'javascript', 'browser',
             'mcp', 'a2a', 'skill', 'accessibility', 'root'},
        )
        self.assertEqual(
            {row[3] for row in catalog},
            {'OPENAI_RESPONSES', 'OPENAI_CHAT_COMPLETIONS', 'ANTHROPIC_MESSAGES'},
        )

    def test_metadata_template_requires_every_reproduction_fact(self):
        text = (ROOT / 'run-metadata.template.json').read_text(encoding='utf-8')
        for field in (
            'datasetSha256', 'provider', 'model', 'providerReportedVersion', 'protocol',
            'temperature', 'promptSha256', 'toolVersions', 'device', 'dateUtc',
            'gitCommit', 'result', 'evidence',
        ):
            with self.subTest(field=field):
                self.assertIn('"' + field + '"', text)

    def test_dataset_digest_is_stable(self):
        expected = (ROOT / 'fixed-evals.sha256').read_text(encoding='utf-8').strip().split(' ', 1)[0]
        actual = hashlib.sha256((ROOT / 'fixed-evals.tsv').read_bytes()).hexdigest()
        self.assertEqual(expected, actual)

    def test_prompt_injection_matrix_covers_ingresses_and_authority_invariants(self):
        matrix = rows(ROOT / 'prompt-injection.tsv')
        self.assertGreaterEqual(len(matrix), 16)
        for row in matrix:
            self.assertEqual(len(row), 5)
        self.assertEqual(len(matrix), len({row[0] for row in matrix}))
        self.assertEqual(
            {row[1] for row in matrix},
            {'WEB', 'FILE', 'MCP', 'A2A', 'SKILL', 'NOTIFICATION'},
        )
        self.assertTrue(all(row[4] == 'no_scope_registry_or_approval_change' for row in matrix))
        self.assertTrue(any(row[0].startswith('pi-filename-') for row in matrix))
        self.assertTrue(any(row[2] == 'fake_system_tag' for row in matrix))
        self.assertTrue(any(row[2] == 'fake_tool_tag' for row in matrix))
        expected = (ROOT / 'prompt-injection.sha256').read_text(encoding='utf-8').strip().split(' ', 1)[0]
        self.assertEqual(expected, hashlib.sha256((ROOT / 'prompt-injection.tsv').read_bytes()).hexdigest())


if __name__ == '__main__':
    unittest.main()
