"""一万个独立隔离账号经真实Session/CSRF执行HTTP持久受理；消费者暂停，绝不称为成交TPS。"""
import argparse
from concurrent.futures import ThreadPoolExecutor
import json
import re
import secrets
import threading
import time
import requests
import perf
from chain_metrics import distribution, snapshot


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', required=True)
    parser.add_argument('--label', required=True)
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,63}', args.label):
        raise RuntimeError('报告标签不合法')
    output = perf.RESULTS / args.label
    if output.exists():
        raise RuntimeError('已有结果不得覆盖')
    perf.connect().close()
    perf.app_stop()
    perf.app_start(args.jar, rate_limit_mode='regression', PURCHASE_CAPACITY='10000',
                   PURCHASE_QUEUE_SECONDS='900', PURCHASE_JOBS_ENABLED='false', OUTBOX_JOBS_ENABLED='false',
                   PURCHASE_ADMISSION_LIMIT_ENABLED='false', CATALOG_CACHE_ENABLED='true')
    fixture = perf.prepare('hot-ample', 1)
    seed = fixture['users'][0]
    password_hash = perf.rows('SELECT password_hash FROM user_account WHERE username=%s', (seed['username'],))[0]['password_hash']
    prefix = 'http10k_' + secrets.token_hex(6)
    accounts = [{'username': prefix + '_' + str(index), 'password': seed['password']} for index in range(10000)]
    with perf.connect() as db, db.cursor() as cur:
        cur.executemany('INSERT INTO user_account(username,password_hash) VALUES(%s,%s)',
                        [(account['username'], password_hash) for account in accounts])
    # 账号Cookie/CSRF各自隔离，仅共享有限TCP传输池，避免一万账号的短连接耗尽发起端端口。
    transport = requests.adapters.HTTPAdapter(pool_connections=64, pool_maxsize=64, pool_block=True, max_retries=0)
    def login(account):
        session = requests.Session()
        session.mount('http://', transport)
        return perf.Client(account, session=session)
    login_started = time.monotonic()
    with ThreadPoolExecutor(max_workers=16) as pool:
        clients = list(pool.map(login, accounts[:5000]))
        print('前5000个账号完成登录，保留原IP安全限流窗口', flush=True)
        while time.monotonic() - login_started < 61:
            time.sleep(1)
        clients.extend(pool.map(login, accounts[5000:]))
    print('一万个独立账号已完成真实Session及CSRF登录', flush=True)
    output.mkdir(parents=True)
    before = perf.db_snapshot()
    stop = threading.Event()
    samples, monitor_failures = [], []
    monitor_client = perf.admin()
    def monitor():
        while not stop.is_set():
            try:
                samples.append(perf.sample(monitor_client))
            except Exception as failure:
                monitor_failures.append(type(failure).__name__)
            stop.wait(2)
    worker = threading.Thread(target=monitor, daemon=True)
    worker.start()
    started = time.monotonic()
    sku = fixture['target']['skuId']
    def submit(index):
        client = clients[index]
        begin = time.monotonic()
        try:
            response = client.session.post(perf.BASE + '/api/v1/orders', json={'skuId': sku, 'quantity': 1},
                headers={**client.token, 'Idempotency-Key': 'http-capacity-once'}, timeout=30)
            body = response.json()
            valid = response.status_code == 202 and body['data']['status'] == 'QUEUED'
            return {'completedSeconds': time.monotonic() - started, 'latencyMs': (time.monotonic() - begin) * 1000,
                    'status': response.status_code, 'validAcceptance': valid,
                    'code': None if valid else body.get('code')}
        except Exception as failure:
            return {'completedSeconds': time.monotonic() - started, 'latencyMs': (time.monotonic() - begin) * 1000,
                    'status': 0, 'validAcceptance': False, 'code': type(failure).__name__}
    try:
        with ThreadPoolExecutor(max_workers=64) as pool:
            results = list(pool.map(submit, range(10000)))
    finally:
        stop.set()
        worker.join(15)
    elapsed = time.monotonic() - started
    after = perf.db_snapshot()
    perf.save(output / 'http-completions.json', results)
    perf.save(output / 'monitoring.json', samples)
    assert not monitor_failures, monitor_failures
    assert all(row['validAcceptance'] for row in results), '受理未全通过，保留现场和完成记录'
    assert perf.rows("SELECT COUNT(*) n,COUNT(DISTINCT user_id) users FROM purchase_request WHERE status='QUEUED'")[0] == {'n': 10000, 'users': 10000}
    assert perf.rows('SELECT pending_count FROM purchase_capacity WHERE id=1')[0]['pending_count'] == 10000
    assert perf.rows('SELECT COUNT(*) n FROM ticket_order')[0]['n'] == 0
    client = clients[0]
    response = client.session.post(perf.BASE + '/api/v1/orders', json={'skuId': sku, 'quantity': 1},
        headers={**client.token, 'Idempotency-Key': 'over-capacity'}, timeout=30)
    assert response.status_code == 503 and response.json()['code'] == 'PURCHASE_QUEUE_FULL'
    recovered = client.request('GET', '/api/v1/purchase-requests/lookup', headers={'Idempotency-Key': 'http-capacity-once'})
    original_deadline = recovered['deadlineAt']
    perf.app_stop()
    perf.app_start(args.jar, rate_limit_mode='regression', PURCHASE_CAPACITY='200',
                   PURCHASE_QUEUE_SECONDS='900', PURCHASE_JOBS_ENABLED='false', OUTBOX_JOBS_ENABLED='false',
                   PURCHASE_ADMISSION_LIMIT_ENABLED='false', CATALOG_CACHE_ENABLED='true')
    client = perf.Client(accounts[0])
    response = client.session.post(perf.BASE + '/api/v1/orders', json={'skuId': sku, 'quantity': 1},
        headers={**client.token, 'Idempotency-Key': 'after-capacity-down'}, timeout=30)
    assert response.status_code == 503 and response.json()['code'] == 'PURCHASE_QUEUE_FULL'
    replay = client.request('POST', '/api/v1/orders', {'skuId': sku, 'quantity': 1},
                            {'Idempotency-Key': 'http-capacity-once'}, statuses=(202,))
    assert replay['requestId'] == recovered['requestId'] and replay['deadlineAt'] == original_deadline
    audit = perf.audit()
    stable = [row for row in results if 10 <= row['completedSeconds'] < 30]
    perf.save(output / 'report.json', {'passed': True, 'environment': 'ticketing-perf-only',
        'distinctAuthenticatedAccounts': 10000, 'submitConcurrency': 64, 'persistentCapacity': 10000,
        'maximumClientTransportConnections': 64, 'independentCookieSessions': 10000,
        'accepted': 10000, 'createdOrders': 0, 'finiteBatchSeconds': elapsed,
        'finiteBatchAcceptanceTps': 10000 / elapsed, 'wholeBatchLatencyMs': distribution(r['latencyMs'] for r in results),
        'steadyWindow': {'warmupSeconds': 10, 'seconds': 20, 'acceptedTps': len(stable) / 20,
                         'latencyMs': distribution(r['latencyMs'] for r in stable)},
        'rejected503AtFullCapacity': True, 'lowerCapacityRejectsNewButReplaysOldWithoutDeadlineRefresh': True,
        'configurationDown': {'oldCapacity': 10000, 'newCapacity': 200, 'existingPending': 10000},
        'mysqlDelta': {key: after['mysql'][key] - value for key, value in before['mysql'].items()},
        'final': after, 'audit': audit, 'chain': snapshot(perf.connect),
        'artifact': json.loads((perf.PRIVATE / 'app.json').read_text(encoding='utf-8')),
        'scope': '有限一万次单次提交，按完成时刻另截取10秒预热后的20秒窗口；认证准备单列。暂停发布和恢复任务，可靠受理不等于正式订单，不外推真实成交或持续容量。'} )
    print('一万个真实HTTP受理、满额拒绝、配置下调与旧键恢复检查通过', flush=True)
    transport.close()


if __name__ == '__main__':
    main()
