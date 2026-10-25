"""250名独立买家的真实HTTP突发，验证默认200持久容量拒绝及重启排空。"""
import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import hashlib
import json
import re
import threading
import time
import uuid
import perf
from chain_metrics import distribution


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', required=True)
    parser.add_argument('--label', required=True)
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9_-]{1,50}', args.label):
        raise RuntimeError('标签无效')
    path = perf.RESULTS / args.label / 'overload.json'
    if path.exists():
        raise RuntimeError('结果已存在')
    perf.connect().close()
    perf.app_stop()
    perf.app_start(args.jar, rate_limit_mode='regression')
    fixture = perf.prepare('hot-ample', 250)
    perf.app_stop()
    perf.app_start(args.jar, rate_limit_mode='regression', PURCHASE_MESSAGING_ENABLED='false', OUTBOX_JOBS_ENABLED='false')
    with ThreadPoolExecutor(max_workers=8) as pool:
        clients = list(pool.map(perf.Client, fixture['users']))
    gate = threading.Event()
    def submit(pair):
        index, client = pair
        gate.wait()
        started = time.monotonic()
        try:
            response = client.session.post(perf.BASE + '/api/v1/orders',
                json={'skuId': fixture['target']['skuId'], 'quantity': 1},
                headers={**client.token, 'Idempotency-Key': 's19-burst-' + str(index) + '-' + uuid.uuid4().hex}, timeout=45)
            body = response.json()
            return {'status': response.status_code, 'code': body.get('code'),
                    'latencyMs': (time.monotonic() - started) * 1000}
        except Exception as error:
            return {'status': 0, 'errorType': type(error).__name__, 'latencyMs': (time.monotonic() - started) * 1000}
    start = time.monotonic()
    with ThreadPoolExecutor(max_workers=250) as pool:
        futures = [pool.submit(submit, pair) for pair in enumerate(clients)]
        gate.set()
        results = [future.result() for future in futures]
    seconds = time.monotonic() - start
    snapshot = perf.db_snapshot()
    audit = perf.audit()
    accepted = sum(r['status'] == 202 for r in results)
    rejected = sum(r['status'] == 503 and r.get('code') == 'PURCHASE_QUEUE_FULL' for r in results)
    outcomes = Counter((r['status'], r.get('code') if re.fullmatch(r'[A-Z_]{1,80}', str(r.get('code', ''))) else
                        r.get('errorType', 'NONE')) for r in results)
    report = {'passed': False, 'at': datetime.now(timezone.utc).isoformat(), 'jarSha256': hashlib.sha256((perf.ROOT / args.jar).read_bytes()).hexdigest(),
        'accounts': 250, 'simultaneousSubmissionWorkers': 250, 'capacity': 200,
        'consumerPausedDuringBurst': True, 'accepted202': accepted, 'expectedCapacity503': rejected,
        'unexpected': len(results) - accepted - rejected, 'submissionSeconds': seconds,
        'outcomes': [{'status': status, 'codeOrErrorType': code, 'count': count}
                     for (status, code), count in sorted(outcomes.items())],
        'httpBurstRps': len(results) / seconds, 'acceptedBurstTps': accepted / seconds,
        'successfulOrderTpsDuringPause': 0, 'acceptanceMs': distribution(r['latencyMs'] for r in results if r['status'] == 202),
        'rejectionMs': distribution(r['latencyMs'] for r in results if r['status'] == 503),
        'snapshotWhilePaused': snapshot, 'auditWhilePaused': audit}
    perf.save(path, report)
    if accepted != 200 or rejected != 50:
        raise RuntimeError('突发未符合200受理/50容量拒绝，保留全部状态，停止加压')
    perf.app_stop()
    drain_start = time.monotonic()
    perf.app_start(args.jar, rate_limit_mode='regression')
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        if perf.rows('SELECT pending_count FROM purchase_capacity WHERE id=1')[0]['pending_count'] == 0:
            break
        time.sleep(.5)
    report['restartAndDrainSeconds'] = time.monotonic() - drain_start
    report['final'] = perf.db_snapshot()
    report['auditAfterRecovery'] = perf.audit()
    report['successfulOrders'] = perf.rows("SELECT COUNT(*) n FROM purchase_request WHERE status='SUCCEEDED'")[0]['n']
    report['capacityRemaining'] = 200 - perf.rows('SELECT pending_count FROM purchase_capacity WHERE id=1')[0]['pending_count']
    report['passed'] = report['successfulOrders'] == 200 and report['capacityRemaining'] == 200
    perf.save(path, report)
    if not report['passed']:
        raise RuntimeError('恢复未达到全部成功建单并释放容量，保留结果')
    print('250独立买家突发与默认容量200拒绝及恢复通过', flush=True)


if __name__ == '__main__':
    main()
