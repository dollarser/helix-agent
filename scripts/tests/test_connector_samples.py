"""No Gradle, network, ADB, or private input is needed for runner contract tests."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import zipfile
from unittest.mock import patch
from types import SimpleNamespace

spec = importlib.util.spec_from_file_location('connector_samples', Path(__file__).resolve().parents[1] / 'connector-samples.py')
samples = importlib.util.module_from_spec(spec)
spec.loader.exec_module(samples)


class ConnectorSamplesTest(unittest.TestCase):
    def test_missing_or_incomplete_manifest_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with self.assertRaises(FileNotFoundError):
                samples.verified_inputs(root)
            (root / 'manifest.json').write_text(json.dumps({'format': 'helix.connector-test-inputs.v1', 'files': []}))
            with self.assertRaises(ValueError):
                samples.verified_inputs(root)

    def test_prepare_is_byte_exact_and_rejects_changed_targets_before_copying(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            supplied = root / 'supplied'
            supplied.mkdir()
            for name in ('qwenwork', 'github', 'kling-ai-plugin'):
                with zipfile.ZipFile(supplied / f'{name}.zip', 'w') as archive:
                    archive.writestr('SKILL.md', '# Test data; do not execute')
            config = supplied / 'example.json'
            config.write_text('{}')
            fetcher = SimpleNamespace(SOURCES={'example.json': ('repo', 'revision', 'config.json')},
                                      EXPECTED_SHA256={'example.json': samples.digest(config)})
            args = SimpleNamespace(qwenwork=supplied / 'qwenwork.zip', workbuddy=supplied,
                                   public_configs=supplied, root=root / 'fixtures')
            with patch.object(samples, 'public_fetcher', return_value=fetcher):
                samples.prepare(args)
                self.assertEqual((args.root / 'qwenwork/qwenwork.zip').read_bytes(), args.qwenwork.read_bytes())
                samples.verified_inputs(args.root)
                target = args.root / 'workbuddy/github.zip'
                target.write_bytes(b'changed')
                with self.assertRaisesRegex(ValueError, 'Sample changed'):
                    samples.verified_inputs(args.root)
                with self.assertRaisesRegex(ValueError, 'Refusing to overwrite'):
                    samples.prepare(args)
                self.assertEqual(target.read_bytes(), b'changed')

    def test_prepare_rejects_invalid_archive_without_partial_output(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            invalid = root / 'qwenwork.zip'
            invalid.write_bytes(b'not a zip')
            args = SimpleNamespace(qwenwork=invalid, workbuddy=root, public_configs=root, root=root / 'output')
            with self.assertRaises(zipfile.BadZipFile):
                samples.prepare(args)
            self.assertFalse(args.root.exists())

    def test_absent_empty_skipped_failed_and_wrong_method_reports_fail(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / 'TEST-com.helix.app.connector.Example.xml'
            with self.assertRaises(FileNotFoundError):
                samples.inspect_reports(root, {'Example': 'works'})
            for body in ('', '<testcase name="other"/>', '<testcase name="works"><skipped/></testcase>',
                         '<testcase name="works"><failure/></testcase>'):
                path.write_text(f'<testsuite>{body}</testsuite>')
                with self.subTest(body=body), self.assertRaises(ValueError):
                    samples.inspect_reports(root, {'Example': 'works'})
            path.write_text('<testsuite><testcase name="works"/></testsuite>')
            self.assertEqual(samples.inspect_reports(root, {'Example': 'works'}), ['Example.works'])

    def test_offline_mode_clears_inherited_network_opt_in_and_forces_fresh_test_task(self):
        with tempfile.TemporaryDirectory() as directory:
            args = SimpleNamespace(root=Path(directory), output=Path(directory) / 'output',
                                   mode='offline', flavor='consumer')
            with patch.object(samples, 'verified_inputs', return_value=[]), \
                    patch.dict(samples.os.environ, {'HELIX_PUBLIC_MCP_ACCEPTANCE': 'true'}), \
                    patch.object(samples.subprocess, 'run', return_value=SimpleNamespace(returncode=1)) as run:
                with self.assertRaises(RuntimeError):
                    samples.test(args)
            command = run.call_args.args[0]
            env = run.call_args.kwargs['env']
            self.assertNotIn('HELIX_PUBLIC_MCP_ACCEPTANCE', env)
            self.assertIn('--rerun', command)
            self.assertNotIn('com.helix.app.connector.ConnectorExternalAcceptanceTest.' +
                             samples.ONLINE['ConnectorExternalAcceptanceTest'], command)

    def test_network_mode_does_not_require_private_fixtures(self):
        with tempfile.TemporaryDirectory() as directory:
            args = SimpleNamespace(root=Path(directory) / 'absent', output=Path(directory) / 'output',
                                   mode='public-mcp', flavor='developer')
            with patch.object(samples, 'verified_inputs') as verify, \
                    patch.object(samples.subprocess, 'run', return_value=SimpleNamespace(returncode=1)) as run:
                with self.assertRaises(RuntimeError):
                    samples.test(args)
                verify.assert_not_called()
            self.assertEqual(run.call_args.kwargs['env']['HELIX_PUBLIC_MCP_ACCEPTANCE'], 'true')
            for key in samples.ENV_KEYS[:3]:
                self.assertNotIn(key, run.call_args.kwargs['env'])
