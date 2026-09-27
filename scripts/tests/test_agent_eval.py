import copy
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import agent_eval as ev


class AgentEvalTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.raw = self.root / 'facts.json'
        self.raw.write_text('{"durable":"COMPLETED"}')
        self.identity = {k: ev.fingerprint(k) for k in ev.HASH_FIELDS}
        self.identity.update(suite='files', caseId='file-001', gitCommit='abc', dirty=False,
                             sourceManifestSha=ev.fingerprint('source'), evidenceKind='fixture',
                             measurementScope='session', flavor='developer', api=36, device='test',
                             provider='scripted', model='fixture-v1', protocol='CHAT', providerVersion='v1')

    def record(self, result='PASS', **metrics):
        return ev.envelope(copy.deepcopy(self.identity), result, {'fileHashMatches': True},
                           [ev.artifact(self.raw, self.root)], metrics)

    def test_missing_metrics_are_unknown_and_unknown_extension_survives(self):
        r = self.record()
        r['extension'] = {'future': 1}
        r['trajectory'].pop('modelCalls')
        actual = ev.validate(r, self.root)
        self.assertIsNone(actual['trajectory']['modelCalls'])
        self.assertEqual(actual['extension'], r['extension'])
        group = ev.aggregate([actual])['groups'][0]
        self.assertEqual(group['metrics']['modelCalls']['known'], 0)
        self.assertIsNone(group['metrics']['modelCalls']['mean'])

    def test_required_identity_and_verifier_missing_never_pass(self):
        for field in ['caseId', 'datasetSha', 'sourceManifestSha', 'verifierSha', 'dirty']:
            r = self.record()
            del r['identity'][field]
            self.assertEqual(ev.validate(r, self.root)['outcome']['verifiedResult'], 'INVALID')
        r = self.record()
        r['outcome']['verifierFacts'] = {}
        self.assertEqual(ev.validate(r, self.root)['outcome']['verifiedResult'], 'INVALID')

    def test_tamper_missing_artifact_and_versions_rejected(self):
        r = self.record()
        self.raw.write_text('changed')
        with self.assertRaises(ev.EvidenceError):
            ev.validate(r, self.root)
        self.raw.unlink()
        with self.assertRaises(ev.EvidenceError):
            ev.validate(r, self.root)
        r['schemaVersion'] = 2
        with self.assertRaises(ev.EvidenceError):
            ev.validate(r, self.root)

    def test_path_escape_rejected(self):
        r = self.record()
        r['outcome']['artifacts'][0]['path'] = '../private'
        with self.assertRaises(ev.EvidenceError):
            ev.validate(r, self.root)

    def test_invalid_metric_types_and_nonfinite_cost(self):
        for field, value in [('turns', -1), ('toolCalls', True), ('firstPassSucceeded', 1)]:
            self.assertEqual(ev.validate(self.record(**{field: value}), self.root)['outcome']['verifiedResult'], 'INVALID')
        r = self.record()
        r['cost']['wallMillis'] = float('nan')
        self.assertEqual(ev.validate(r, self.root)['outcome']['verifiedResult'], 'INVALID')
        self.assertEqual(ev.validate(self.record('FAIL', firstPassSucceeded=True), self.root)['outcome']['verifiedResult'], 'INVALID')

    def test_duplicate_records_rejected(self):
        with self.assertRaises(ev.EvidenceError):
            ev.aggregate([self.record(), self.record()])

    def test_cohorts_never_pool_real_and_fixture(self):
        a, b = self.record(), self.record('FAIL')
        b['identity']['evidenceKind'] = 'real-provider'
        self.assertEqual(len(ev.aggregate([a, b])['groups']), 2)

    def test_device_provider_and_configuration_cohorts_are_separate(self):
        for key, value in [('device', {'model': 'different'}), ('provider', 'other'), ('sessionConfigHash', ev.fingerprint('changed'))]:
            a, b = self.record(), self.record()
            b['identity'][key] = value
            self.assertEqual(len(ev.aggregate([a, b])['groups']), 2)

    def test_manifest_selectors_resolve_to_real_test_methods(self):
        root = Path(__file__).resolve().parents[2]
        for name in ('host-boundaries', 'core-device'):
            manifest = ev.read_json(root / 'evals/trajectory' / (name + '.json'))
            self.assertEqual(len(manifest['cases']), 8)
            self.assertEqual(len({c['id'] for c in manifest['cases']}), 8)
            source_set = 'test' if name == 'host-boundaries' else 'androidTest'
            for case in manifest['cases']:
                for ref in case['tests']:
                    suffix = f"src/{source_set}/kotlin/" + ref['class'].replace('.', '/') + '.kt'
                    matches = [root / module / suffix for module in ('app', 'core/agent', 'core/storage') if (root / module / suffix).is_file()]
                    self.assertEqual(len(matches), 1, ref)
                    text = matches[0].read_text()
                    self.assertTrue('fun ' + ref['method'] + '(' in text or 'fun `' + ref['method'] + '`(' in text, ref)

    def test_rates_use_known_denominators(self):
        a, b, c = self.record(recoveryEvents=1, firstPassSucceeded=False), self.record('FAIL'), self.record('BLOCKED')
        b['identity']['caseId'] = 'file-002'
        c['identity']['caseId'] = 'file-003'
        stats = ev.statistics([a, b, c])
        self.assertEqual(stats['rates']['taskSuccess']['rate'], .5)
        self.assertEqual(stats['rates']['recoverySuccess']['denominator'], 1)
        self.assertEqual(stats['metrics']['firstPassSucceeded']['known'], 1)

    def test_control_changes_block_delta_and_explicit_treatment_is_allowed(self):
        a, b = self.record(), self.record()
        b['identity']['sessionConfigHash'] = ev.fingerprint('memory-on')
        self.assertFalse(ev.compare([a], [b], {})['comparable'])
        treatment = {'sessionConfigHash': {'before': a['identity']['sessionConfigHash'], 'after': b['identity']['sessionConfigHash']}}
        self.assertTrue(ev.compare([a], [b], treatment)['comparable'])
        b['identity']['fixtureSha'] = ev.fingerprint('other-directory')
        self.assertFalse(ev.compare([a], [b], treatment)['comparable'])
        with self.assertRaises(ev.EvidenceError):
            ev.compare([a], [b], {'fixtureSha': {}})

    def test_missing_cases_unknown_controls_and_invalid_results_block_comparison(self):
        a, b = self.record(), self.record()
        self.assertFalse(ev.compare([a], [], {})['comparable'])
        b['identity']['providerVersion'] = 'UNAVAILABLE'
        self.assertFalse(ev.compare([a], [b], {})['comparable'])
        b = self.record('INVALID')
        self.assertIsNone(ev.compare([a], [b], {})['delta'])

    def test_delta_pairs_only_known_measurements_and_allows_different_builds(self):
        a, b = self.record(modelCalls=2), self.record(modelCalls=3)
        b['identity']['gitCommit'] = 'new'
        b['identity']['sourceManifestSha'] = ev.fingerprint('new')
        r = ev.compare([a], [b], {})
        self.assertTrue(r['comparable'])
        self.assertEqual(r['delta']['metrics']['modelCalls']['meanDelta'], 1)
        self.assertIsNone(r['delta']['metrics']['humanInterventions']['meanDelta'])

    def test_three_fixed_suite_formats_preserve_raw_and_strip_text(self):
        for prefix in ('file', 'browser', 'goal'):
            raw = {'id': prefix + '-001', 'result': 'PASS', 'turnState': 'COMPLETED',
                   'gitCommit': 'abc', 'datasetSha256': ev.fingerprint('data'), 'promptSha256': ev.fingerprint('prompt'),
                   'fixtureContextSha256': ev.fingerprint('fixture'), 'calls': ['read:COMPLETED'],
                   'text': 'untrusted private text', 'toolResults': [{'content': 'private'}]}
            self.raw.write_text(json.dumps(raw))
            before = self.raw.read_bytes()
            r = ev.normalize_m10(self.raw, self.identity, self.root)
            self.assertEqual(r['outcome']['verifiedResult'], 'PASS')
            self.assertEqual(r['trajectory']['toolCalls'], 1)
            self.assertIsNone(r['trajectory']['modelCalls'])
            self.assertNotIn('private', json.dumps(r))
            self.assertEqual(self.raw.read_bytes(), before)

    def test_junit_missing_skip_failure_and_duplicate_are_not_pass(self):
        path = self.root / 'results.xml'
        path.write_text('<testsuite><testcase classname="A" name="ok" time=".1"/><testcase classname="A" name="skip"><skipped/></testcase><testcase classname="A" name="bad"><failure/></testcase></testsuite>')
        manifest = {'schemaVersion': 1, 'suite': 'core', 'coverage': 'host-boundary', 'evidenceKind': 'fixture',
                    'cases': [{'id': name, 'tests': [{'class': 'A', 'method': name}]} for name in ('ok', 'skip', 'bad', 'absent')]}
        result = ev.normalize_junit(manifest, [path], self.identity, self.root)
        self.assertEqual([r['outcome']['verifiedResult'] for r in result], ['PASS', 'BLOCKED', 'FAIL', 'INVALID'])
        with self.assertRaises(ev.EvidenceError):
            ev.junit_cases([path, path])

    def test_empty_suite_or_case_cannot_pass(self):
        with self.assertRaises(ev.EvidenceError):
            ev.normalize_junit({'schemaVersion': 1, 'cases': []}, [], self.identity, self.root)
        with self.assertRaises(ev.EvidenceError):
            ev.normalize_junit({'schemaVersion': 1, 'cases': [{'id': 'empty', 'tests': []}]}, [], self.identity, self.root)

    def test_boolean_schema_version_is_rejected(self):
        r = self.record()
        r['schemaVersion'] = True
        with self.assertRaises(ev.EvidenceError):
            ev.validate(r, self.root)

    def test_manifest_includes_untracked_and_tracked_deletion(self):
        subprocess.run(['git', 'init', '-q', str(self.root)], check=True)
        (self.root / 'src').mkdir()
        tracked = self.root / 'src/tracked.txt'
        tracked.write_text('tracked')
        subprocess.run(['git', 'add', 'src'], cwd=self.root, check=True)
        a = ev.source_manifest(self.root, ['src'])
        (self.root / 'src/new.txt').write_text('untracked')
        b = ev.source_manifest(self.root, ['src'])
        self.assertNotEqual(a['sha256'], b['sha256'])
        tracked.unlink()
        c = ev.source_manifest(self.root, ['src'])
        self.assertIn({'path': 'src/tracked.txt', 'sha256': None}, c['entries'])


if __name__ == '__main__':
    unittest.main()
