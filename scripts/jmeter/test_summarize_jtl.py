"""校验购票窗口、跨窗口终态和缺失事务的统计口径。"""

import csv
import importlib.util
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location('summarize_jtl', Path(__file__).with_name('summarize-jtl.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
POLL, SUBMIT, TRANSACTION, summarize = module.POLL, module.SUBMIT, module.TRANSACTION, module.summarize


class CohortSummaryTest(unittest.TestCase):
    def summarize_rows(self, rows):
        fields = ['timeStamp', 'elapsed', 'label', 'idempotencyKey', 'responseCode',
                  'requestId', 'purchaseOutcome', 'orderId', 'failureCode']
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'samples.jtl'
            with path.open('w', encoding='utf8', newline='') as stream:
                writer = csv.DictWriter(stream, fieldnames=fields)
                writer.writeheader()
                writer.writerows(rows)
            return summarize(path, 1000, 2000)

    def test_window_post_completed_after_end(self):
        rows = [
            dict(timeStamp=1500, elapsed=30, label=SUBMIT, idempotencyKey='a', responseCode='202', requestId='10'),
            dict(timeStamp=2200, elapsed=20, label=POLL, idempotencyKey='a', requestId='10', purchaseOutcome='SUCCEEDED', orderId='20'),
            dict(timeStamp=1490, elapsed=730, label=TRANSACTION, idempotencyKey='a', requestId='10', purchaseOutcome='SUCCEEDED', orderId='20'),
        ]
        result, cohort = self.summarize_rows(rows)
        self.assertTrue(result['complete'])
        self.assertEqual((result['posts'], result['p99Ms']), (1, 720))
        self.assertEqual(cohort[0]['observedAtMs'], 2220)

    def test_missing_transaction_is_censored(self):
        result, _ = self.summarize_rows([
            dict(timeStamp=1500, elapsed=30, label=SUBMIT, idempotencyKey='a', responseCode='202', requestId='10'),
        ])
        self.assertFalse(result['complete'])
        self.assertEqual(result['counts']['missing_transaction'], 1)
        self.assertEqual(result['counts']['outcome_UNOBSERVED'], 1)
        self.assertIsNone(result['p99Ms'])

    def test_window_uses_post_start_and_counts_late_post(self):
        rows = [
            dict(timeStamp=999, elapsed=30, label=SUBMIT, idempotencyKey='early', responseCode='202', requestId='1'),
            dict(timeStamp=2000, elapsed=30, label=SUBMIT, idempotencyKey='late', responseCode='503'),
        ]
        result, _ = self.summarize_rows(rows)
        self.assertEqual(result['posts'], 0)
        self.assertEqual(result['counts']['posts_after_window'], 1)


if __name__ == '__main__':
    unittest.main()
