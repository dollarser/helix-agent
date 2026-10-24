"""Validate the frozen synthetic experiment; no model quality is inferred here."""
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]


class MemoryLogExperimentPlanTest(unittest.TestCase):
    def setUp(self):
        self.plan = json.loads((ROOT / 'scripts/fixtures/memory-log-experiment-v1.json').read_text())

    def test_budget_and_pair_count_are_fixed(self):
        self.assertEqual(1, self.plan['version'])
        self.assertEqual(10, 2 * len(self.plan['memoryCases']) + 2)
        self.assertEqual(4, self.plan['maxWireRequestsPerCase'])
        self.assertEqual(40, self.plan['maxWireRequestsTotal'])
        self.assertEqual(900000, self.plan['totalTimeoutMs'])

    def test_answer_is_not_prefilled_in_any_user_task(self):
        for case in self.plan['memoryCases']:
            marker = case.get('expected', case.get('forbidden'))
            self.assertNotIn(marker, case['question'])
            for topic in case['topics']:
                self.assertNotIn(marker, topic['source'])
        self.assertNotIn(self.plan['log']['expected'], self.plan['log']['question'])

    def test_tail_fact_really_falls_after_the_current_prefix(self):
        case = self.plan['memoryCases'][0]
        body = case['summaryPrefix'] * case['repeatPrefix'] + case['summaryTail']
        self.assertGreater(body.index(case['expected']), 1000)
        self.assertNotIn(case['expected'], body[:1000])

    def test_scope_and_stale_counterexamples_are_present(self):
        cases = {case['id']: case for case in self.plan['memoryCases']}
        self.assertTrue(cases['permission-denied']['denyMemory'])
        self.assertIn(cases['permission-denied']['forbidden'], cases['permission-denied']['summaryPrefix'])
        self.assertNotIn(cases['other-project']['forbidden'], cases['other-project']['summaryPrefix'])
        stale = cases['stale']
        self.assertNotIn(stale['expected'], stale['summaryPrefix'])
        self.assertTrue(any(stale['expected'] in topic['body'] for topic in stale['topics']))
