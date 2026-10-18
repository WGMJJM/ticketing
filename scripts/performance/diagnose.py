"""无后台干扰下记录接口SQL次数，并用独立连接验证用户锁的串行范围。"""
from concurrent.futures import ThreadPoolExecutor
import json
import time
import perf


def main():
    app = json.loads((perf.PRIVATE/'app.json').read_text(encoding='utf-8'))
    perf.app_stop()
    perf.app_start(app['jar'], WAITLIST_JOBS_ENABLED='false', WAITLIST_MESSAGING_ENABLED='false', ORDER_EXPIRY_ENABLED='false')
    try:
        fixture = perf.prepare('mixed',10)
        buyer = perf.Client(fixture['users'][0])
        result = {'sqlCounts':{}}
        def trace(name, action):
            perf.execute('TRUNCATE performance_schema.events_statements_summary_by_digest')
            value = action()
            patterns = perf.rows("""SELECT DIGEST_TEXT sql_pattern,COUNT_STAR calls,
                ROUND(AVG_TIMER_WAIT/1000000000,3) avg_ms FROM performance_schema.events_statements_summary_by_digest
                WHERE SCHEMA_NAME='ticketing_perf' AND DIGEST_TEXT REGEXP '^(SELECT|UPDATE|INSERT)'
                  AND DIGEST_TEXT NOT LIKE '%%performance_control%%'
                  AND DIGEST_TEXT NOT LIKE '%%performance_schema%%' ORDER BY DIGEST_TEXT""")
            result['sqlCounts'][name] = {'calls':sum(int(r['calls']) for r in patterns),'patterns':patterns}
            return value
        created = trace('create',lambda:buyer.order(fixture['target']['skuId']))
        trace('cancel',lambda:buyer.request('POST',f'/api/v1/orders/{created["id"]}/cancel'))
        paid = buyer.order(fixture['target']['skuId'])
        trace('pay',lambda:buyer.request('POST',f'/api/v1/orders/{paid["id"]}/pay'))
        a1 = perf.Client(fixture['users'][0]); a2 = perf.Client(fixture['users'][0]); b = perf.Client(fixture['users'][1])
        user_id = perf.rows('SELECT id FROM user_account WHERE username=%s',(fixture['users'][0]['username'],))[0]['id']
        def timed(client, sku):
            started = time.monotonic()
            order = client.order(sku)
            return {'elapsedMs':round((time.monotonic()-started)*1000,2),'id':order['id']}
        with ThreadPoolExecutor(max_workers=3) as pool, perf.connect() as db, db.cursor() as cur:
            db.begin(); cur.execute('SELECT id FROM user_account WHERE id=%s FOR UPDATE',(user_id,))
            first = pool.submit(timed,a1,fixture['mixed'][1]['skuId'])
            second = pool.submit(timed,a2,fixture['mixed'][2]['skuId'])
            independent = pool.submit(timed,b,fixture['mixed'][2]['skuId'])
            independent_result = independent.result(timeout=5)
            time.sleep(0.25)
            blocked = not first.done() and not second.done()
            db.commit()
            one, two = first.result(timeout=10), second.result(timeout=10)
        assert blocked, '同一用户跨场次请求没有遵守既有用户锁协议'
        result['userLockScope'] = {'sameUserDifferentSessionsBlocked':blocked,
                                  'sameUserLatencyMs':[one['elapsedMs'],two['elapsedMs']],
                                  'differentUserLatencyMs':independent_result['elapsedMs'],
                                  'injectedMinimumHoldMs':250}
        for client, order in [(a1,one),(a2,two),(b,independent_result)]:
            client.request('POST',f'/api/v1/orders/{order["id"]}/cancel')
        result['audit'] = perf.audit()
        result['plans'] = perf.diagnostics()
        result['mysqlLockMetrics'] = perf.rows("SELECT NAME,COUNT,STATUS FROM information_schema.innodb_metrics WHERE NAME IN ('lock_deadlocks','lock_timeouts')")
        perf.save(perf.RESULTS/'sql-and-lock-diagnostics.json',result)
        print('SQL次数和用户跨场次串行范围已用真实HTTP及独立数据库连接验证')
    finally:
        perf.app_stop();perf.app_start(app['jar'],**app.get('overrides',{}))


if __name__=='__main__':main()
