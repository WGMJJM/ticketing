import unittest
from http_qps_stats import http_summary, http_stability, valid_window


def post(start=100, finish=110, code='202', elapsed=10, key='a', account='1'):
    return dict(postStartMs=str(start),httpCompletedMs=str(finish),responseCode=code,
                postElapsedMs=str(elapsed),idempotencyKey=key,accountIndex=account,requestId='123')


class HttpQpsStatsTest(unittest.TestCase):
    def test_window_uses_completion_and_excludes_right_boundary(self):
        result=http_summary([post(90,100),post(199,200,key='b',account='2')],100,200)
        self.assertEqual(result['httpResponses'],1)
        self.assertEqual(result['httpQps'],10)

    def test_all_http_responses_count_but_only_202_latency_counts(self):
        rows=[post(code=c,elapsed=e,key=str(i),account=str(i)) for i,(c,e) in enumerate(
            [('202',12),('202',15),('429',8000),('503',9999),('NETWORK_ERROR',15000)])]
        result=http_summary(rows,0,1000)
        self.assertEqual(result['httpQps'],4)
        self.assertEqual(result['networkFailures'],1)
        self.assertEqual(result['acceptedHttpP99Ms'],15)
        self.assertEqual(result['httpCodes'],{'202':2,'429':1,'503':1})

    def test_p99_excludes_sampler_wait_and_order_settlement(self):
        row=post(elapsed=17)
        row.update(elapsed='9000',orderCompletedMs='999999')
        self.assertEqual(http_summary([row],0,1000)['acceptedHttpP99Ms'],17)

    def test_nearest_rank_99(self):
        rows=[post(elapsed=i,key=str(i),account=str(i)) for i in range(1,101)]
        self.assertEqual(http_summary(rows,0,1000)['acceptedHttpP99Ms'],99)

    def test_duplicate_accounts_and_keys_are_invalid(self):
        s=http_summary([post(),post()],0,1000)
        self.assertEqual(s['duplicateKeys'],1)
        self.assertEqual(s['duplicateAccounts'],1)
        self.assertFalse(valid_window(s,True,None,True,True,False,True))

    def test_posts_started_after_stop_are_detected(self):
        s=http_summary([post(start=150,finish=160)],0,1000,140)
        self.assertEqual(s['postsAfterStop'],1)
        self.assertFalse(valid_window(s,True,None,True,True,False,True))

    def test_resources_rejections_drain_and_confirmation_gate_validity(self):
        s=http_summary([post()],0,1000)
        self.assertTrue(valid_window(s,True,None,True,True,False,True))
        for args in [(False,None,True,True,False,True),(True,'persistent_queue_growth',True,True,False,True),
                     (True,'host_memory_guard',True,True,False,True),(True,None,False,True,False,True),
                     (True,None,True,False,False,True),(True,None,True,True,True,True),
                     (True,None,True,True,False,False)]:
            self.assertFalse(valid_window(s,*args))

    def test_queue_growth_prevents_stability(self):
        posts=[post(finish=i,key=str(i),account=str(i)) for i in range(1000,16000,1000)]
        samples=[dict(timeMs=i,redisPending=i//100) for i in range(1000,16000,2000)]
        self.assertFalse(http_stability(posts,samples,16000)['stable'])
        for sample in samples: sample['redisPending']=1
        self.assertTrue(http_stability(posts,samples,16000)['stable'])


if __name__=='__main__': unittest.main()
