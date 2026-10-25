"""Drain checks must preserve fault-test evidence without ignoring live application queues."""
import unittest
from unittest.mock import patch

import throughput_tuning as tuning


class DrainSafetyTests(unittest.TestCase):
    def queues(self, busy=None):
        return [{'name': name, 'messages_ready': int(name == busy), 'messages_unacknowledged': 0}
                for name in tuning.LIVE_QUEUES]

    def check(self, queues, active=0, budget=0, events=0):
        return (patch.object(tuning, 'guard_process'),
                patch.object(tuning.perf, 'rows', side_effect=lambda sql: [
                    {'pending_count': budget} if 'SELECT pending_count' in sql
                    else {'n': events if 'outbox_event' in sql else active}]),
                patch.object(tuning.perf, 'mq_snapshot', return_value=queues),
                patch.object(tuning.perf, 'audit'), patch.object(tuning.time, 'sleep'))

    def run_check(self, queues, **counts):
        from contextlib import ExitStack
        with ExitStack() as stack:
            mocks = [stack.enter_context(p) for p in self.check(queues, **counts)]
            tuning.require_drained()
            mocks[3].assert_called_once()

    def test_integration_dead_letters_are_preserved_and_excluded(self):
        queues = self.queues() + [{'name': 'ticketing.purchase.stage14.it.orders.dead',
                                 'messages_ready': 99, 'messages_unacknowledged': 0}]
        self.run_check(queues)

    def test_live_or_dead_application_message_blocks_reset(self):
        for queue in tuning.LIVE_QUEUES:
            with self.subTest(queue=queue), self.assertRaisesRegex(RuntimeError, 'not drained'):
                self.run_check(self.queues(queue))

    def test_missing_application_queue_blocks_reset(self):
        with self.assertRaisesRegex(RuntimeError, 'Missing'):
            self.run_check(self.queues()[:-1])

    def test_database_backlog_or_budget_blocks_reset(self):
        for field in ('active', 'budget', 'events'):
            with self.subTest(field=field), self.assertRaisesRegex(RuntimeError, 'not drained'):
                self.run_check(self.queues(), **{field: 1})


if __name__ == '__main__':
    unittest.main()
