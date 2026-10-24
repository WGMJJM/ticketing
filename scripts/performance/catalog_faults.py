"""第15阶段真实Redis停机、目录受控回源与恢复，保留既有安全过滤器故障关闭。"""
import argparse
from concurrent.futures import ThreadPoolExecutor
import json
import time
import uuid
import re
import requests
import perf


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', required=True)
    parser.add_argument('--label', default='stage15')
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9_-]{1,40}', args.label): raise RuntimeError('标签格式错误')
    path = perf.ROOT / 'docs/architecture/results' / ('catalog-faults-' + args.label + '.json')
    if path.exists(): raise RuntimeError('故障结果已存在，必须保留并改用新标签')
    perf.connect().close()
    perf.app_stop()
    perf.app_start(args.jar, rate_limit_mode='regression', CATALOG_CACHE_ENABLED='true', PURCHASE_ADMISSION_LIMIT_ENABLED='true')
    perf.prepare('query', 20)
    fixture = json.loads((perf.PRIVATE / 'input.json').read_text(encoding='utf-8'))
    target = fixture['target']
    url = perf.BASE + '/api/v1/activities/' + target['activityId']
    buyer = perf.Client(fixture['users'][0])
    key = 'redis-outage-' + uuid.uuid4().hex
    accepted = buyer.request('POST', '/api/v1/orders', {'skuId': target['skuId'], 'quantity': 1}, {'Idempotency-Key': key}, statuses=(202,))
    buyer.resolve_purchase(accepted)
    assert requests.get(url, timeout=5).status_code == 200
    report = {'environment': 'ticketing-perf-only', 'passed': False, 'checks': []}
    def check(name, condition, **facts):
        if not condition: raise RuntimeError('故障验证失败 ' + name)
        report['checks'].append({'name': name, 'passed': True, **facts})
        perf.save(path, report)
    before = perf.rows('SELECT COUNT(*) n FROM purchase_request')[0]['n']
    commands_before = perf.rows("SELECT COUNT(*) n FROM outbox_event WHERE aggregate_type='PURCHASE_REQUEST'")[0]['n']
    operator = perf.admin()
    version = perf.rows('SELECT version FROM activity WHERE id=%s', (target['activityId'],))[0]['version']
    operator.request('POST', '/api/v1/admin/activities/' + target['activityId'] + '/offline', {'version': version})
    with ThreadPoolExecutor(max_workers=20) as pool:
        hidden = list(pool.map(lambda _: requests.get(url, timeout=5).status_code, range(20)))
    check('管理提交下架后20并发不命中旧公开数据', hidden == [404] * 20)
    operator.request('POST', '/api/v1/admin/activities/' + target['activityId'] + '/publish', {'version': version + 1})
    with ThreadPoolExecutor(max_workers=20) as pool:
        reloaded = list(pool.map(lambda _: requests.get(url, timeout=5).status_code, range(20)))
    check('重新发布失效后20并发热键正确重建', reloaded == [200] * 20)
    try:
        perf.compose('stop', 'redis')
        # 保留真实会话，覆盖本地TTL后访问，禁止把L1命中当作Redis断连回源成功。
        time.sleep(16)
        with ThreadPoolExecutor(max_workers=20) as pool:
            statuses = list(pool.map(lambda _: requests.get(url, timeout=5).status_code, range(20)))
        check('Redis停机且L1过期后20并发公开详情受控回源', statuses == [200] * 20)
        body = {'skuId': target['skuId'], 'quantity': 1}
        response = buyer.session.post(perf.BASE + '/api/v1/orders', json=body,
                headers={**buyer.token, 'Idempotency-Key': 'new-' + key}, timeout=5)
        check('Redis停机时原安全链路对新请求503关闭且未受理', response.status_code == 503 and response.json()['code'] == 'RATE_LIMIT_UNAVAILABLE',
              httpStatus=response.status_code, code=response.json()['code'])
        replay = buyer.session.post(perf.BASE + '/api/v1/orders', json=body, headers={**buyer.token, 'Idempotency-Key': key}, timeout=5)
        check('Redis停机时原用户安全过滤器也会先拒绝幂等回放', replay.status_code == 503 and replay.json()['code'] == 'RATE_LIMIT_UNAVAILABLE')
        check('故障拒绝没有新增受理及容量占用', perf.rows('SELECT COUNT(*) n FROM purchase_request')[0]['n'] == before
              and perf.rows('SELECT pending_count n FROM purchase_capacity WHERE id=1')[0]['n'] == 0
              and perf.rows("SELECT COUNT(*) n FROM outbox_event WHERE aggregate_type='PURCHASE_REQUEST'")[0]['n'] == commands_before)
    finally:
        perf.compose('start', 'redis')
    for _ in range(30):
        try:
            response = buyer.session.post(perf.BASE + '/api/v1/orders', json={'skuId': target['skuId'], 'quantity': 1},
                    headers={**buyer.token, 'Idempotency-Key': key}, timeout=5)
            if response.status_code == 200: break
        except requests.RequestException: pass
        time.sleep(1)
    check('Redis恢复后原请求回放仍指向同一正式订单', response.status_code == 200 and response.json()['data']['requestId'] == accepted['requestId'])
    time.sleep(16)
    check('Redis恢复后公开查询重新填充缓存', requests.get(url, timeout=5).status_code == 200)
    time.sleep(16)
    check('恢复后L1再次过期仍能命中Redis', requests.get(url, timeout=5).status_code == 200)
    from catalog_suite import metric_snapshot
    counters = metric_snapshot()
    check('公开缓存记录真实Redis故障并已恢复读取', counters['catalog.detail.redis-unavailable'] > 0 and counters['catalog.detail.l2'] > 0,
          counters=counters)
    report['audit'] = perf.audit()
    report['passed'] = True
    perf.save(path, report)
    print('真实Redis故障及恢复验证通过')


if __name__ == '__main__': main()
