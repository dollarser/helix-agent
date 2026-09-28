import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
SUMMARIZER = ROOT / 'scripts/debug/2026-09-28/summarize-p5-local-model.py'
sys.path.insert(0, str(ROOT / 'scripts'))
import agent_eval


class P5LocalModelSummaryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def seed(self, identity=None):
        config = {'key': 'fixture', 'runnerExit': 0, **(identity or {})}
        (self.root / 'p5-config.json').write_text(json.dumps(config))
        (self.root / 'artifacts.json').write_text(json.dumps({'app': 'a' * 64, 'test': 'b' * 64}))
        (self.root / 'device-properties.json').write_text(
            json.dumps({'api': '36', 'abi': 'arm64-v8a', 'fingerprint': 'fixture', 'avd': 'Fixture'})
        )
        (self.root / 'emulator-config.json').write_text(json.dumps({'memoryMb': 8192, 'cores': 4}))
        (self.root / 'closed.json').write_text(json.dumps({'exit': 0}))
        (self.root / 'events.txt').write_text(
            'START probe1 context=4096\n'
            '10ms TextDelta(text=ok)\n11ms Usage(inputTokens=10, outputTokens=1)\n12ms Completed(finishReason=stop)\n'
            'START probe2 context=4096\n20ms ToolCallStarted(index=0, id=x, name=echo)\n'
            '21ms Usage(inputTokens=20, outputTokens=5)\n22ms Completed(finishReason=tool_calls)\n'
            'START task1 context=4096\n100ms Usage(inputTokens=100, outputTokens=20)\n'
            '101ms Completed(finishReason=tool_calls)\n'
        )
        (self.root / 'baseline-phases.tsv').write_text('1000\taggregation-task\tstart\n1200\tgeneration:task1\tend\n')
        (self.root / 'runtime-memory.txt').write_text(
            'DEVICE_UPTIME_MS=1050 HOST_MONOTONIC=1\n TOTAL PSS: 123 TOTAL RSS: 456\n'
        )
        (self.root / 'trajectory.txt').write_text(
            'TurnEntity(id=t, state=COMPLETED, errorCode=null)\n'
            'ToolCallEntity(id=w, name=write, argsJson={"path":"scope:ws:totals.csv"}, state=COMPLETED)\n'
            'ToolCallEntity(id=r, name=read, argsJson={"path":"scope:ws:totals.csv"}, state=COMPLETED)\n'
            'ToolCallEntity(id=w2, name=write, argsJson={"path":"scope:ws:report.md"}, state=COMPLETED)\n'
            'ToolCallEntity(id=r2, name=read, argsJson={"path":"scope:ws:report.md"}, state=COMPLETED)\n'
        )
        (self.root / 'totals.csv').write_text('region,orders,returns,net\nEast,150,10,140\nWest,120,20,100\n')
        (self.root / 'report.md').write_text('grand net 240')
        (self.root / 'probe.txt').write_text('Ok')
        (self.root / 'real-model-instrumentation.txt').write_text('OK (2 tests)')
        (self.root / 'p5-lifecycle.json').write_text(
            json.dumps({'cancelToExitMs': 10, 'unloadMs': 5, 'secondLoadMs': 20, 'terminateMs': 30})
        )

    def summarize(self):
        subprocess.run([sys.executable, str(SUMMARIZER), str(self.root)], cwd=ROOT, check=True, capture_output=True)
        return json.loads((self.root / 'p5-summary.json').read_text())

    def test_legacy_inflight_run_stays_non_comparable(self):
        self.seed()
        summary = self.summarize()
        self.assertEqual('INCOMPLETE', summary['comparisonIdentity']['status'])
        self.assertFalse((self.root / 'agent-eval-envelopes.json').exists())

    def test_identity_aware_run_emits_four_valid_hxa227_envelopes(self):
        self.seed(
            {
                'taskSetSha': '1' * 64,
                'gitCommit': 'deadbeef',
                'dirty': True,
                'sourceManifestSha': '2' * 64,
                'sha': '3' * 64,
                'size': 123,
            }
        )
        summary = self.summarize()
        self.assertIsNone(summary['nativeTimingObservability']['trueFirstTokenMs'])
        records = json.loads((self.root / 'agent-eval-envelopes.json').read_text())
        validated = [agent_eval.validate(record, self.root) for record in records]
        self.assertEqual(
            ['no-tool-text', 'tool-capability', 'file-aggregation', 'cancel-exit-lifecycle'],
            [record['identity']['caseId'] for record in validated],
        )
        self.assertTrue(all(record['outcome']['verifiedResult'] == 'PASS' for record in validated))
        self.assertTrue(all(record['identity']['appApkSha'] == 'a' * 64 for record in validated))


    def test_correct_files_without_durable_completion_fail_as_budget(self):
        self.seed(
            {
                'taskSetSha': '1' * 64,
                'gitCommit': 'deadbeef',
                'dirty': True,
                'sourceManifestSha': '2' * 64,
                'sha': '3' * 64,
                'size': 123,
            }
        )
        trajectory = self.root / 'trajectory.txt'
        trajectory.write_text(trajectory.read_text().replace('state=COMPLETED', 'state=RECEIVING_MODEL', 1))
        self.summarize()
        records = json.loads((self.root / 'agent-eval-envelopes.json').read_text())
        aggregation = next(record for record in records if record['identity']['caseId'] == 'file-aggregation')
        validated = agent_eval.validate(aggregation, self.root)
        self.assertEqual('FAIL', validated['outcome']['verifiedResult'])
        self.assertEqual('BUDGET', validated['outcome']['failureCategory'])
        self.assertTrue(validated['outcome']['verifierFacts']['totalsCorrect'])
        self.assertFalse(validated['outcome']['verifierFacts']['durableTurnCompleted'])


if __name__ == '__main__':
    unittest.main()
