import unittest
from diagnosis_stats import SafetyGate, summarize, has_persistent_pending_growth, success_cohorts, warmup_stability, capacity_pressure_reason


class DiagnosisStatsTest(unittest.TestCase):
    def test_full_capacity_closed_loop_requires_explicit_opt_in_and_still_rejects_overflow(self):
        self.assertEqual(capacity_pressure_reason(150, 0, 200), 'redis_pending_reaches_75_percent_capacity')
        self.assertEqual(capacity_pressure_reason(0, 150, 200), 'queue_reaches_75_percent_capacity')
        self.assertIsNone(capacity_pressure_reason(200, 200, 200, True))
        self.assertEqual(capacity_pressure_reason(201, 0, 200, True), 'redis_pending_exceeds_capacity')
        self.assertEqual(capacity_pressure_reason(0, 201, 200, True), 'queue_exceeds_capacity')

    def test_warmup_requires_flat_successful_completion_rates(self):
        def samples(counts):
            return [dict(completedMs=15000+i*5000+n,status='SUCCEEDED') for i,count in enumerate(counts) for n in range(count)]
        self.assertTrue(warmup_stability(samples([100,108,104]),30000)['stable'])
        self.assertFalse(warmup_stability(samples([60,90,130]),30000)['stable'])
        self.assertFalse(warmup_stability([],30000)['stable'])

    def test_warmup_request_committing_in_measurement_counts_tps_but_not_latency_cohort(self):
        posts=[dict(postStartMs=t,idempotencyKey=str(i),requestId=str(i),postElapsedMs=5,responseCode='202')
               for i,t in ((1,19000),(2,21000))]
        orders=[dict(id=i,idempotency_key=str(i),status='SUCCEEDED',order_id=i,created_ms=t,accepted_ms=t-1000)
                for i,t in ((1,20500),(2,22000))]
        formal=summarize(posts,orders,20000,50000)
        self.assertEqual(formal['orderCount'],2)
        self.assertEqual(formal['accepted'],1)
        self.assertEqual(formal['successfulDbWithout202'],0)
        self.assertEqual(summarize(posts,orders,1000,50000)['accepted'],2)

    def test_recovered_503_keeps_fixed_tps_but_separate_latency_and_unknown_fails_closed(self):
        def post(key,code,completed,fail=''):
            return dict(idempotencyKey=key,requestId=key,postStartMs=1000,postElapsedMs=5,
                responseCode=code,failureCode=fail,orderStatus='SUCCEEDED',orderCompletedMs=completed,orderObservedMs=completed+10)
        posts=[post('1','202',1100),post('2','503',1800,'PURCHASE_PUBLISH_UNCONFIRMED')]
        orders=[dict(id=i,idempotency_key=str(i),status='SUCCEEDED',order_id=i,created_ms=1050,accepted_ms=1010) for i in (1,2)]
        self.assertEqual(summarize(posts,orders,1000,2000)['orderTps'],2)
        cohort=success_cohorts(posts,orders)
        self.assertEqual(cohort['success202']['postToSettledMs']['p99'],100)
        self.assertEqual(cohort['recoveredNon202']['postToSettledMs']['p99'],800)
        unknown=post('3','503',1900,'PURCHASE_ADMISSION_UNAVAILABLE')
        self.assertEqual(success_cohorts([unknown],[])['unknownKeys'],['3'])
        unknown['failureCode']='PURCHASE_QUEUE_FULL'
        self.assertEqual(success_cohorts([unknown],[])['non202FinalOutcomes'],{'PURCHASE_QUEUE_FULL:DEFINITE_NO_PREHOLD':1})

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
