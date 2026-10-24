"""暂停独立RabbitMQ，在真实批量认领后终止发布进程，验证持久租约与重启恢复。"""
import argparse
import re
import time
import perf


def until(predicate, seconds=120):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        result = predicate()
        if result:
            return result
        time.sleep(.2)
    raise RuntimeError('发布器崩溃恢复未在限定时间完成，保留现场')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', required=True)
    parser.add_argument('--label', required=True)
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9_-]{1,40}', args.label):
        raise RuntimeError('报告标签不合法')
    output = perf.ROOT / 'docs/architecture/results' / ('publisher-crash-' + args.label + '.json')
    if output.exists():
        raise RuntimeError('已有结果不得覆盖')
    perf.connect().close()
    perf.app_stop()
    perf.app_start(args.jar, rate_limit_mode='regression', PURCHASE_QUEUE_SECONDS='600',
                   PURCHASE_ADMISSION_LIMIT_ENABLED='false', CATALOG_CACHE_ENABLED='true')
    fixture = perf.prepare('query', 1)
    client = perf.Client(fixture['users'][0])
    user = perf.rows('SELECT id FROM user_account WHERE username=%s', (fixture['users'][0]['username'],))[0]['id']
    sku = fixture['target']['skuId']
    report = {'passed': False, 'environment': 'ticketing-perf-only', 'checks': []}
    def check(name, condition, **facts):
        if not condition:
            raise RuntimeError('发布器崩溃验证失败 ' + name)
        report['checks'].append({'name': name, 'passed': True, **facts})
        perf.save(output, report)
    def events():
        return perf.rows("""SELECT p.id request_id,e.status,e.retry_count FROM outbox_event e JOIN purchase_request p
            ON e.event_id=CONCAT('purchase-',p.id) WHERE e.event_type='PURCHASE_REQUEST_ACCEPTED' AND p.user_id=%s""", (user,))
    try:
        perf.compose('pause', 'rabbitmq')
        accepted = [client.request('POST', '/api/v1/orders', {'skuId': sku, 'quantity': 1},
                    {'Idempotency-Key': 'publisher-crash-' + str(index)}, statuses=(202,)) for index in range(3)]
        inflight = until(lambda: events() if len(events()) == 3 and any(e['status'] == 'PROCESSING' for e in events()) else None, 15)
        tokens = {e['request_id']: e['retry_count'] for e in inflight if e['status'] == 'PROCESSING'}
        check('真实进程认领后代理暂停且库存仍未预占', len(inflight) == 3 and
              perf.rows('SELECT locked_stock FROM ticket_sku WHERE id=%s', (sku,))[0]['locked_stock'] == 0,
              accepted=3, claimed=len(tokens), attempts=[e['retry_count'] for e in inflight])
        perf.app_stop()  # 已验证PID归属，真实终止Java进程，不手工改Outbox或请求状态。
        check('进程终止后持久PROCESSING租约仍存在', any(e['status'] == 'PROCESSING' for e in events()))
        perf.compose('unpause', 'rabbitmq')
        perf.app_start(args.jar, rate_limit_mode='regression', PURCHASE_QUEUE_SECONDS='600',
                       PURCHASE_ADMISSION_LIMIT_ENABLED='false', CATALOG_CACHE_ENABLED='true')
        client = perf.Client(fixture['users'][0])
        orders = [client.resolve_purchase(item)['id'] for item in accepted]
        settled = until(lambda: events() if all(e['status'] == 'SENT' for e in events()) else None)
        check('重启依靠租约重新认领且三笔可靠受理各建单一次', len(set(orders)) == 3 and
              all(e['retry_count'] > tokens[e['request_id']] for e in settled if e['request_id'] in tokens) and
              perf.rows('SELECT COUNT(*) n FROM ticket_order WHERE user_id=%s', (user,))[0]['n'] == 3 and
              perf.rows('SELECT pending_count FROM purchase_capacity WHERE id=1')[0]['pending_count'] == 0,
              orders=3, reclaimedInflight=len(tokens), minimumAttempts=min(e['retry_count'] for e in settled))
        for order in orders:
            client.request('POST', '/api/v1/orders/' + order + '/cancel')
        until(lambda: not perf.rows("SELECT id FROM outbox_event WHERE status IN('PENDING','PROCESSING') LIMIT 1"))
        report['audit'] = perf.audit()
        report['passed'] = True
        perf.save(output, report)
        print('真实发布进程在途崩溃及持久租约恢复通过', flush=True)
    finally:
        perf.compose('unpause', 'rabbitmq', check=False)
        perf.app_stop()


if __name__ == '__main__':
    main()
