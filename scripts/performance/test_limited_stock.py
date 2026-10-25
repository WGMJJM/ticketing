"""Safety and reconciliation checks for the destructive isolated fixture runner."""
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from unittest.mock import MagicMock

import limited_stock_1000 as experiment


class LimitedStockSafetyTests(unittest.TestCase):
    def test_labels_and_load_bounds_fail_before_environment_access(self):
        for label, buyers, workers in (('../escape', 1000, 64), ('valid', 1001, 64),
                                       ('valid', 1000, 1000), ('valid', 1000, 0)):
            with self.subTest(label=label, buyers=buyers, workers=workers), self.assertRaises(RuntimeError):
                experiment.validate_options(label, buyers, workers)

    def test_existing_public_or_private_result_refuses_overwrite(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with patch.object(experiment.perf, 'RESULTS', root / 'results'), patch.object(experiment.perf, 'PRIVATE', root / 'private'):
                for parent in (experiment.perf.RESULTS, experiment.perf.PRIVATE):
                    (parent / 'used').mkdir(parents=True)
                    with self.assertRaisesRegex(RuntimeError, 'overwritten'):
                        experiment.validate_options('used', 1000, 64)

    def test_experiment_lock_rejects_another_runner(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(experiment.perf, 'PRIVATE', Path(directory)):
            with experiment.exclusive_run():
                with self.assertRaises(FileExistsError):
                    with experiment.exclusive_run():
                        self.fail('Second runner entered')
            self.assertFalse((Path(directory) / 'limited-stock.lock').exists())

    def test_protection_rejections_and_unknown_errors_are_distinct(self):
        cases = [(202, 'OK', '123', 'accepted202'), (202, 'OK', None, 'otherUnexpected'),
                 (503, 'PURCHASE_QUEUE_FULL', None, 'admissionRejected'),
                 (409, 'TICKET_UNAVAILABLE', None, 'knownPrecheckRejected'),
                 (429, 'RATE_LIMITED', None, 'otherUnexpected'),
                 (503, 'REDIS_UNAVAILABLE', None, 'transportFailures'),
                 (401, 'UNAUTHORIZED', None, 'transportFailures'),
                 (0, 'ConnectionError', None, 'transportFailures')]
        for status, code, request_id, expected in cases:
            with self.subTest(status=status, code=code):
                self.assertEqual(experiment.classification(status, code, request_id), expected)

    def test_reconciliation_detects_wrong_owner_missing_and_duplicate_requests(self):
        accepted = {'ordinal': 0, 'category': 'accepted202', 'requestId': '9', 'key': 'unique',
                    'terminalStatus': 'SUCCEEDED', 'orderId': '7', 'failureCode': None, 'orderHttpValid': True}
        database = {'id': 9, 'user_id': 11, 'idempotency_key': 'unique', 'status': 'SUCCEEDED',
                    'order_id': 7, 'failure_code': None}
        self.assertEqual(experiment.reconcile([accepted], [database], {0: 11}), 0)
        self.assertGreater(experiment.reconcile([accepted], [database], {0: 12}), 0)
        self.assertGreater(experiment.reconcile([accepted], [], {0: 11}), 0)
        self.assertGreater(experiment.reconcile([accepted, accepted], [database], {0: 11}), 0)

    def test_public_results_remove_identity_and_idempotency_mapping(self):
        value = experiment.public_record({'ordinal': 1, 'key': 'secret-key', 'requestId': '2',
                                          'orderId': '3', 'relogged': True, 'status': 202})
        self.assertEqual(value, {'ordinal': 1, 'status': 202})

    def test_only_diagnostic_group_expands_capacity_and_deadline(self):
        for scenario in ('A', 'C'):
            self.assertEqual(experiment.settings(scenario, False)['PURCHASE_CAPACITY'], '200')
            self.assertEqual(experiment.settings(scenario, False)['PURCHASE_QUEUE_SECONDS'], '120')
        self.assertEqual(experiment.settings('B', False)['PURCHASE_CAPACITY'], '1000')
        self.assertEqual(experiment.settings('B', False)['PURCHASE_QUEUE_SECONDS'], '900')
        self.assertEqual(experiment.settings('B', True)['PURCHASE_MESSAGING_ENABLED'], 'false')
        self.assertEqual(experiment.settings('C', False)['PURCHASE_MESSAGING_ENABLED'], 'true')

    def test_terminal_idempotent_replay_allows_http_200(self):
        client = MagicMock()
        client.request.return_value = {'requestId': '9'}
        client.session.post.return_value.status_code = 409
        client.session.post.return_value.json.return_value = {'code': 'IDEMPOTENCY_CONFLICT'}
        record = {'ordinal': 0, 'category': 'accepted202', 'requestId': '9', 'key': 'once'}
        with patch.object(experiment.perf, 'rows', return_value=[{'requests': 1, 'orders': 1, 'budget': 0}]):
            result = experiment.check_replay([record], [client], '1')
        self.assertIn(200, client.request.call_args.kwargs['statuses'])
        self.assertTrue(all(result.values()))


if __name__ == '__main__':
    unittest.main()
