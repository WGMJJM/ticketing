"""第15阶段同一JAR单变量A/B：目录缓存四档负载与Lua准入三场景；只使用固定隔离环境。"""
import argparse
import json
from pathlib import Path
from types import SimpleNamespace
import re
import perf


def metric_snapshot():
    client = perf.admin()
    result = {}
    selections = []
    for kind in ('detail', 'sessions', 'list'):
        for outcome in ('l1', 'l2', 'db', 'negative', 'busy', 'redis-unavailable', 'load-failure'):
            selections.append(('catalog.' + kind + '.' + outcome, 'ticketing.catalog.requests', [('tag', 'kind:' + kind), ('tag', 'outcome:' + outcome)]))
    for outcome in ('allowed', 'limited-global', 'limited-ticket', 'unavailable'):
        selections.append(('admission.' + outcome, 'ticketing.purchase.admission.requests', [('tag', 'outcome:' + outcome)]))
    for label, name, tags in selections:
        response = client.session.get(perf.BASE + perf.MONITOR + '/metrics/' + name, params=tags, timeout=5)
        if response.status_code == 404:
            result[label] = 0
        elif response.status_code == 200:
            result[label] = sum(v['value'] for v in response.json().get('measurements', []) if v['statistic'] == 'COUNT')
        else:
            raise RuntimeError('指标读取失败 ' + str(response.status_code))
    for label, name, tags in [('catalog.redis.read', 'ticketing.catalog.redis.duration', [('tag', 'operation:read')]),
                               ('catalog.redis.write', 'ticketing.catalog.redis.duration', [('tag', 'operation:write')]),
                               ('admission.redis', 'ticketing.purchase.admission.redis.duration', [])]:
        response = client.session.get(perf.BASE + perf.MONITOR + '/metrics/' + name, params=tags, timeout=5)
        if response.status_code not in (200, 404): raise RuntimeError('Redis计时指标读取失败')
        values = {value['statistic']: value['value'] for value in response.json().get('measurements', [])} if response.status_code == 200 else {}
        for suffix, statistic in [('count', 'COUNT'), ('totalSeconds', 'TOTAL_TIME'), ('maxSeconds', 'MAX')]:
            result[label + '.' + suffix] = values.get(statistic, 0)
    return result


def report(label, expected_rounds=14):
    root = perf.RESULTS / label
    rows = []
    for path in sorted(root.glob('*/*/environment.json')):
        environment = json.loads(path.read_text(encoding='utf-8'))
        metrics = json.loads((path.parent / 'k6-summary.json').read_text(encoding='utf-8'))['metrics']
        observability = json.loads((path.parent / 'feature-metrics.json').read_text(encoding='utf-8'))
        def count(name): return metrics.get(name, {}).get('values', {}).get('count', 0)
        def latency(name):
            value = metrics.get(name, {}).get('values', {})
            return {key: value.get(key) for key in ('med', 'p(95)', 'p(99)')}
        seconds = environment['steadySeconds']
        samples = json.loads((path.parent / 'monitoring.json').read_text(encoding='utf-8'))
        row = {'mode': path.parent.parent.name, 'scenario': environment['scenario'], 'vus': environment['vus'],
               'httpRps': count('steady_requests') / seconds, 'acceptedTps': count('accepted_purchase_requests') / seconds,
               'orderTps': count('created_orders') / seconds, 'acceptanceMs': latency('steady_acceptance_latency'),
               'endToEndMs': latency('steady_purchase_completion_latency'), 'businessFailures': count('purchase_business_failures'),
               'limitedGlobal': count('admission_rate_limited{code:PURCHASE_GLOBAL_BUSY}'),
               'limitedTicket': count('admission_rate_limited{code:PURCHASE_TICKET_BUSY}'),
               'serverErrors': count('server_errors'), 'transportErrors': count('transport_errors'),
               'mysqlSelectWholeRun': environment['mysqlDelta']['Com_select'],
               'mysqlRowLockMs': environment['mysqlDelta']['Innodb_row_lock_time'], 'featureCountersWholeRun': observability,
               'outboxSampledPeak': max((sum(value['n'] for value in sample['outbox'] if value['status'] in ('PENDING', 'PROCESSING')) for sample in samples), default=0),
               'purchaseQueueSampledPeak': max((int(queue.get('messages_ready') or 0) + int(queue.get('messages_unacknowledged') or 0)
                    for sample in samples for queue in sample.get('rabbitmq', []) if queue.get('name') == 'ticketing.purchase.orders'), default=0),
               'queryLatencyMs': {route: latency('steady_latency{name:' + route + '}') for route in ('activities', 'activity', 'sessions', 'tickets')},
               'finalOutbox': environment['final']['outbox'], 'finalChannelLag': environment['final']['outboxChannelLag'],
               'finalRequests': environment['final']['purchaseRequests'], 'artifactSha256': environment['app']['sha256'],
               'auditPassed': environment['audit']['passed'], 'k6ExitCode': environment['k6ExitCode']}
        rows.append(row)
    if len(rows) != expected_rounds:
        raise RuntimeError('预定轮次尚未齐全，禁止标记完成')
    if len({row['artifactSha256'] for row in rows}) != 1:
        raise RuntimeError('A/B必须使用同一JAR')
    result = {'environment': 'ticketing-perf-only', 'protocol': 'stage15-cache-and-admission-v1', 'rounds': rows,
              'measurement': 'HTTP及终态指标按请求完成时间截取稳定窗口；SELECT及特性计数为包含预热和监测的整轮差值，不除以稳定秒数冒充稳定TPS',
              'passed': all(row['auditPassed'] and row['k6ExitCode'] == 0 and row['serverErrors'] == 0 and row['transportErrors'] == 0 for row in rows)}
    perf.save(root / 'comparison.json', result)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', required=True)
    parser.add_argument('--label', default='stage15')
    parser.add_argument('--report-only', action='store_true')
    parser.add_argument('--metrics-supplement', action='store_true', help='额外两轮采集Redis计时及并发指标，不混入原十四轮A/B')
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,40}', args.label): raise RuntimeError('标签格式错误')
    if args.report_only:
        report(args.label, 2 if args.metrics_supplement else 14)
        return
    root = perf.RESULTS / args.label
    if root.exists(): raise RuntimeError('原始结果目录已存在，必须使用新标签')
    perf.connect().close()
    modes = [('cache-off', False, False), ('cache-on', True, False), ('admission-off', True, False), ('admission-on', True, True)]
    if args.metrics_supplement: modes = [('cache-timing', True, False), ('admission-timing', True, True)]
    for mode, cache, admission in modes:
        perf.app_stop()
        perf.app_start(args.jar, rate_limit_mode='regression', CATALOG_CACHE_ENABLED=str(cache).lower(), PURCHASE_ADMISSION_LIMIT_ENABLED=str(admission).lower())
        original = perf.RESULTS
        try:
            perf.RESULTS = root / mode
            perf.environment_snapshot()
        finally:
            perf.RESULTS = original
        scenarios = [('query', vus) for vus in (10, 25, 50, 100)] if mode.startswith('cache-') else [('hot-ample', 100), ('hot-scarce', 100), ('mixed', 25)]
        if args.metrics_supplement: scenarios = [('query' if mode == 'cache-timing' else 'hot-ample', 100)]
        for scenario, vus in scenarios:
            print(mode, scenario, vus, flush=True)
            perf.prepare(scenario, vus)
            before = metric_snapshot()
            options = SimpleNamespace(label=args.label + '/' + mode, scenario=scenario, vus=vus, warmup=10, duration=20,
                                      k6=None, activity_id=None, session_id=None, sku_id=None, admission_rejections=admission)
            # perf标签继续保持单段校验；通过结果根目录定位分组，避免放宽既有路径约束。
            options.label = mode
            original = perf.RESULTS
            try:
                perf.RESULTS = root
                perf.run(options)
            finally:
                perf.RESULTS = original
            after = metric_snapshot()
            perf.save(root / mode / (scenario + '-' + str(vus)) / 'feature-metrics.json', {
                key: after[key] if key.endswith('.maxSeconds') else after[key] - before.get(key, 0) for key in after})
    result = report(args.label, 2 if args.metrics_supplement else 14)
    if not result['passed']: raise RuntimeError('A/B未通过')
    print('预定轮次及审计通过', flush=True)


if __name__ == '__main__': main()
