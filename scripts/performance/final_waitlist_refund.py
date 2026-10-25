"""100候补与50笔模拟退款/50笔取消交织，真实HTTP及同步出票；有限批次不冒充稳定TPS。"""
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import hashlib
import re
import threading
import time
import perf


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', required=True)
    parser.add_argument('--label', required=True)
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9_-]{1,50}', args.label):
        raise RuntimeError('标签无效')
    output = perf.RESULTS / args.label / 'waitlist-refund.json'
    if output.exists():
        raise RuntimeError('结果已存在')
    perf.connect().close()
    perf.app_stop()
    perf.app_start(args.jar, rate_limit_mode='regression', ORDER_PAYMENT_WINDOW_MINUTES='15')
    fixture = perf.prepare('waitlist', 100)
    with ThreadPoolExecutor(max_workers=8) as pool:
        buyers = list(pool.map(perf.Client, fixture['users']))
        holders = list(pool.map(perf.Client, fixture['admins']))
    with ThreadPoolExecutor(max_workers=8) as pool:
        list(pool.map(lambda pair: pair[0].request('POST', '/api/v1/orders/' + pair[1] + '/pay'),
                      zip(holders[:50], fixture['holderOrders'][:50])))
    operator = perf.admin()
    before = perf.db_snapshot()
    samples, failures = [], []
    stop = threading.Event()
    def monitor():
        client = perf.admin()
        while not stop.is_set():
            try:
                samples.append(perf.sample(client))
            except Exception as error:
                failures.append(type(error).__name__)
            stop.wait(2)
    worker = threading.Thread(target=monitor, daemon=True)
    worker.start()
    started = time.monotonic()
    def release(index):
        order = fixture['holderOrders'][index]
        if index < 50:
            result = operator.request('POST', '/api/v1/admin/orders/' + order + '/refund', {'reason': 'CUSTOMER_REQUEST'})
            if result['status'] != 'SUCCEEDED':
                raise RuntimeError('有效夹具退款未成功')
        else:
            holders[index].request('POST', '/api/v1/orders/' + order + '/cancel')
    def complete(index):
        client = buyers[index]
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            entry = client.request('GET', '/api/v1/waitlist/' + fixture['waitlistIds'][index])
            if entry['status'] == 'ALLOCATED':
                client.request('POST', '/api/v1/orders/' + entry['reservedOrderId'] + '/pay')
                return
            if entry['status'] != 'WAITING':
                raise RuntimeError('候补异常终态')
            time.sleep(.25)
        raise RuntimeError('候补120秒未完成')
    try:
        with ThreadPoolExecutor(max_workers=10) as release_pool, ThreadPoolExecutor(max_workers=10) as buyer_pool:
            release_tasks = [release_pool.submit(release, index) for index in range(100)]
            purchase_tasks = [buyer_pool.submit(complete, index) for index in range(100)]
            for task in release_tasks + purchase_tasks:
                task.result()
    finally:
        stop.set(); worker.join(timeout=15)
    elapsed = time.monotonic() - started
    final = perf.db_snapshot()
    rows = perf.rows("SELECT w.id,w.reserved_order_id,w.status,o.created_at FROM waitlist_entry w JOIN ticket_order o ON o.id=w.reserved_order_id ORDER BY w.id")
    fifo = all(rows[i]['reserved_order_id'] < rows[i + 1]['reserved_order_id'] for i in range(len(rows) - 1))
    report = {'passed': len(rows) == 100 and all(r['status'] == 'COMPLETED' for r in rows) and fifo and not failures,
        'at': datetime.now(timezone.utc).isoformat(), 'jarSha256': hashlib.sha256((perf.ROOT / args.jar).read_bytes()).hexdigest(),
        'independentWaiters': 100, 'independentHolders': 100, 'refunds': 50, 'cancellations': 50,
        'completedWaiters': len(rows), 'fifoOrderIdsIncreasing': fifo, 'finiteBatchSeconds': elapsed,
        'finiteBatchCompletedAndPaidPerSecond': 100 / elapsed, 'before': before, 'final': final,
        'mysqlDelta': {key: final['mysql'][key] - value for key, value in before['mysql'].items()},
        'monitorFailures': failures, 'audit': perf.audit(), 'samples': samples,
        'scope': '退款/取消与候补建单、模拟支付交织的有限批次；不是稳定成功订单TPS。'}
    perf.save(output, report)
    if not report['passed']:
        raise RuntimeError('候补退款交织验收未通过')
    print('100候补/50退款/50取消真实交织及FIFO/全审计通过', flush=True)


if __name__ == '__main__':
    main()
