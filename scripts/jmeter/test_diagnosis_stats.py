import unittest
from diagnosis_stats import SafetyGate, summarize, has_persistent_pending_growth


class DiagnosisStatsTest(unittest.TestCase):
    def test_delayed_rabbit_statistics_do_not_stop_bounded_closed_loop(self):
        observed = [dict(redisPending=p, mq={'ready':q}) for p,q in zip((58,58,57,58,59,60),(0,0,49,49,50,50))]
        self.assertFalse(has_persistent_pending_growth(observed))
        self.assertTrue(has_persistent_pending_growth([dict(redisPending=p) for p in (10,20,30,40,50)]))
        self.assertFalse(has_persistent_pending_growth([dict(redisPending=p) for p in (10,30,20,40,50)]))

    def test_boundaries_drain_rejection_and_missing_id(self):
        posts = [dict(postStartMs=t, idempotencyKey=str(t), requestId=str(t), postElapsedMs=10, responseCode=code)
                 for t, code in [(999, '202'), (1000, '202'), (1999, '503'), (2000, '202')]]
        orders = [dict(id=1000, idempotency_key='1000', status='SUCCEEDED', order_id=7,
                       created_ms=2100, accepted_ms=1010)]
        result = summarize(posts, orders, 1000, 2000)
        self.assertEqual((result['attempted'], result['accepted'], result['orderTps'], result['drainOrders']), (2, 1, 0, 1))
        self.assertEqual(result['serverQueueAndCreateMs']['p99'], 1090)
        posts[1]['requestId'] = ''
        self.assertEqual(summarize(posts, orders, 1000, 2000)['acceptedMissingRequestId'], 1)

    def test_duplicate_censoring_and_unique_orders(self):
        post = dict(postStartMs=1000, idempotencyKey='k', requestId='1', postElapsedMs=5, responseCode='202')
        result = summarize([post, post], [], 1000, 2000)
        self.assertEqual(result['duplicatePostKeys'], 1)
        self.assertEqual(result['incompleteAccepted'], 2)
        self.assertFalse(result['completeAccepted'])
        self.assertIsNone(result['serverQueueAndCreateMs']['p99'])

    def test_db_window_is_independent_of_http_acceptance_and_deduplicated(self):
        posts = [dict(postStartMs=1100, scheduledMs=1090, idempotencyKey='k', requestId='',
                      postElapsedMs=30, responseCode='503', failureCode='PURCHASE_PUBLISH_UNCONFIRMED')]
        first = dict(id=1, idempotency_key='k', status='SUCCEEDED', order_id=7,
                     created_ms=1000, accepted_ms=900)
        at_end = dict(id=2, idempotency_key='other', status='SUCCEEDED', order_id=8,
                      created_ms=2000, accepted_ms=1900)
        result = summarize(posts, [first, first, at_end], 1000, 2000)
        self.assertEqual(result['orderTps'], 1)
        self.assertEqual(result['orderTpsBy5Seconds'], [dict(startMs=1000,endMs=2000,orders=1,tps=1)])
        self.assertEqual(result['acceptedRps'], 0)
        self.assertEqual(result['drainOrders'], 1)
        self.assertEqual(result['successfulDbWithout202'], 2)
        self.assertEqual(result['failureCodes'], {'PURCHASE_PUBLISH_UNCONFIRMED': 1})
        self.assertEqual(result['arrivalLatenessMs']['p99'], 10)

    def test_cpu_duration_and_fail_closed_monitor(self):
        gate = SafetyGate()
        for t in (0, 2, 4):
            self.assertIsNone(gate.update(dict(monotonic=t, cpu=96, freeGiB=8, ramUsedPercent=50)))
        self.assertEqual(gate.update(dict(monotonic=6, cpu=96, freeGiB=8, ramUsedPercent=50)), 'host_cpu_over_95_5s')
        gate = SafetyGate()
        gate.update(dict(monotonic=0, cpu=20, freeGiB=8, ramUsedPercent=50))
        self.assertEqual(gate.update(dict(monotonic=6, cpu=20, freeGiB=8, ramUsedPercent=50)), 'monitor_gap_over_5s')

    def test_memory_stops_without_weakening_threshold(self):
        gate = SafetyGate()
        self.assertEqual(gate.update(dict(monotonic=0, cpu=20, freeGiB=3.99, ramUsedPercent=60)), 'host_memory_guard')


if __name__ == '__main__':
    unittest.main()
