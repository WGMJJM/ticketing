"""Bounded, guarded steady-load comparisons through the real purchase and cancellation chain."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time
from types import SimpleNamespace

import perf
import final_jfr
from limited_stock_1000 import exclusive_run, guard_process
from metric_quantiles import snapshot as timer_snapshot


LIVE_QUEUES = {'ticketing.purchase.orders', 'ticketing.purchase.orders.dead',
               'ticketing.waitlist.dispatch', 'ticketing.waitlist.dispatch.dead'}


def require_drained():
    guard_process()
    for _ in range(15):
        active = perf.rows("SELECT COUNT(*) n FROM purchase_request WHERE status IN('QUEUED','PROCESSING')")[0]['n']
        budget = perf.rows('SELECT pending_count FROM purchase_capacity WHERE id=1')[0]['pending_count']
        events = perf.rows("SELECT COUNT(*) n FROM outbox_event WHERE status<>'SENT'")[0]['n']
        queues = [q for q in perf.mq_snapshot() if q['name'] in LIVE_QUEUES]
        if {q['name'] for q in queues} != LIVE_QUEUES:
            raise RuntimeError('Missing dedicated application queues')
        messages = sum((q.get('messages_ready') or 0) + (q.get('messages_unacknowledged') or 0) for q in queues)
        if active == budget == events == messages == 0:
            perf.audit()
            return
        time.sleep(1)
    raise RuntimeError('Application requests, budget, Outbox or live/dead queues have not drained')


def summarize(folder, seconds):
    environment = json.loads((folder / 'environment.json').read_text(encoding='utf-8'))
    metrics = json.loads((folder / 'k6-summary.json').read_text(encoding='utf-8'))['metrics']
    samples = json.loads((folder / 'monitoring.json').read_text(encoding='utf-8'))
    def count(name):
        return metrics.get(name, {}).get('values', {}).get('count', 0)
    def latency(name):
        values = metrics.get(name, {}).get('values', {})
        return {key: values.get(key) for key in ('med', 'p(95)', 'p(99)', 'max')}
    def peak(name, statistic='VALUE'):
        return max((measurement['value'] for row in samples
                    for measurement in row.get('jvm', {}).get(name, [])
                    if measurement['statistic'] == statistic), default=0)
    chain = json.loads((folder / 'chain.json').read_text(encoding='utf-8'))
    return {'successfulOrderTps': count('created_orders') / seconds,
            'acceptedTps': count('accepted_purchase_requests') / seconds,
            'httpRps': count('steady_requests') / seconds,
            'successCompletionMs': latency('steady_purchase_success_latency'),
            'acceptanceMs': latency('steady_acceptance_latency'),
            'createHttpMs': latency('steady_latency{name:create}'),
            'ordersInSteadyWindow': count('created_orders'),
            'businessFailures': count('purchase_business_failures'),
            'capacityRejections': count('admission_rejected'),
            'serverErrors': count('server_errors'), 'transportErrors': count('transport_errors'),
            'poolActivePeak': peak('hikaricp.connections.active'),
            'poolPendingPeak': peak('hikaricp.connections.pending'),
            'poolTimeouts': peak('hikaricp.connections.timeout', 'COUNT'),
            'cpuPeak': peak('process.cpu.usage'),
            'mysqlDelta': environment['mysqlDelta'], 'auditPassed': environment['audit']['passed'],
            'k6ExitCode': environment['k6ExitCode'], 'monitorFailures': environment['monitorFailures'],
            'chain': chain}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', required=True)
    parser.add_argument('--label', required=True)
    parser.add_argument('--vus', nargs='+', type=int, required=True)
    parser.add_argument('--consumers', type=int, choices=(2, 4, 8), default=2)
    parser.add_argument('--pool', type=int, choices=(10, 16, 20, 24), default=10)
    parser.add_argument('--outbox-poll-ms', type=int, choices=(100, 250, 500, 1000), default=1000)
    parser.add_argument('--local-session-state', action='store_true')
    parser.add_argument('--warmup', type=int, default=15)
    parser.add_argument('--seconds', type=int, default=60)
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,49}', args.label):
        raise RuntimeError('Invalid unique result label')
    if not 5 <= args.warmup <= 60 or not 20 <= args.seconds <= 300:
        raise RuntimeError('Only bounded warmup and steady windows are allowed')
    if (not 1 <= len(args.vus) <= 3 or len(set(args.vus)) != len(args.vus)
            or any(v not in (8, 32, 64, 128) for v in args.vus)):
        raise RuntimeError('Use up to three conservative concurrency steps')
    directory = perf.RESULTS / args.label
    if directory.exists() or (perf.PRIVATE / args.label).exists():
        raise RuntimeError('Existing labels must never be overwritten')
    jar = Path(args.jar).resolve()
    config = {'PURCHASE_CONSUMER_CONCURRENCY': str(args.consumers),
              'PURCHASE_CONSUMER_PREFETCH': '1',
              'SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE': str(args.pool),
              'PURCHASE_CAPACITY': '200', 'PURCHASE_QUEUE_SECONDS': '120',
              'WAITLIST_OUTBOX_IN_FLIGHT_WINDOW': '16',
              'WAITLIST_OUTBOX_POLL_MS': str(args.outbox_poll_ms),
              'PERF_JDBC_LOCAL_SESSION_STATE': str(args.local_session_state).lower(),
              'ORDER_PAYMENT_WINDOW_MINUTES': '15',
              'CATALOG_CACHE_ENABLED': 'true', 'PURCHASE_ADMISSION_LIMIT_ENABLED': 'false'}
    report = {'passed': False, 'baselineCommit': subprocess.check_output(
        ['git', 'rev-parse', 'HEAD'], text=True).strip(),
        'jarSha256': hashlib.sha256(jar.read_bytes()).hexdigest(),
        'runnerSha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        'config': config, 'rateLimitMode': 'regression', 'warmupSeconds': args.warmup,
        'steadySeconds': args.seconds, 'rounds': []}
    with exclusive_run():
        require_drained()
        report['excludedIntegrationQueues'] = [q for q in perf.mq_snapshot() if q['name'] not in LIVE_QUEUES]
        perf.save(directory / 'comparison.json', report)
        perf.environment_snapshot(directory / 'machine.json')
        for vus in args.vus:
            require_drained()
            guard_process()
            perf.app_stop()
            perf.app_start(str(jar), rate_limit_mode='regression', **config)
            require_drained()
            perf.prepare('hot-ample', vus)
            output = directory / str(vus) / f'hot-ample-{vus}'
            original = perf.RESULTS
            try:
                perf.RESULTS = directory
                recording = final_jfr.begin(args.label + '-' + str(vus))
                try:
                    perf.run(SimpleNamespace(label=str(vus), scenario='hot-ample', vus=vus,
                        warmup=args.warmup, duration=args.seconds, k6=None, activity_id=None,
                        session_id=None, sku_id=None, admission_rejections=False,
                        live_monitor=True, preauthenticate=True))
                finally:
                    final_jfr.finish(recording, output)
            finally:
                perf.RESULTS = original
            require_drained()
            perf.save(output / 'timer-quantiles.json', timer_snapshot(perf.admin(), perf.BASE, perf.MONITOR))
            row = {'vus': vus, **summarize(output, args.seconds)}
            report['rounds'].append(row)
            perf.save(directory / 'comparison.json', report)
            if (not row['auditPassed'] or row['serverErrors'] or row['transportErrors']
                    or row['businessFailures'] or row['capacityRejections'] or row['poolTimeouts']
                    or any(row['chain']['purchaseStates'][s] for s in ('FAILED', 'EXPIRED', 'QUEUED', 'PROCESSING'))
                    or row['mysqlDelta'].get('lock_deadlocks', 0)
                    or row['mysqlDelta'].get('lock_timeouts', 0)):
                raise RuntimeError('Stop increasing load after a failed round; preserve evidence')
            print(json.dumps({'vus': vus, 'tps': row['successfulOrderTps'],
                              'successMs': row['successCompletionMs'], 'poolPending': row['poolPendingPeak']}), flush=True)
        report['passed'] = True
        perf.save(directory / 'comparison.json', report)
        guard_process()
        perf.app_stop()


if __name__ == '__main__':
    main()
