"""最终同JAR反序双轮、阶梯与30分钟稳定性；沿用真实202链路及完成时间窗口。"""
import argparse
import hashlib
import json
import re
from datetime import datetime, timezone
from types import SimpleNamespace
import perf


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', required=True)
    parser.add_argument('--label', required=True)
    parser.add_argument('--mode', choices=['baseline', 'steps', 'soak', 'waitlist'], required=True)
    parser.add_argument('--reverse', action='store_true')
    parser.add_argument('--steady-seconds', type=int, default=60)
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9_-]{1,50}', args.label) or not 20 <= args.steady_seconds <= 3600:
        raise RuntimeError('标签或稳定窗口不合法')
    directory = perf.RESULTS / args.label
    if directory.exists():
        raise RuntimeError('结果已存在，禁止覆盖')
    perf.connect().close()
    perf.app_stop()
    perf.app_start(args.jar, rate_limit_mode='regression', WAITLIST_OUTBOX_IN_FLIGHT_WINDOW='16',
                   PURCHASE_CONSUMER_CONCURRENCY='2', PURCHASE_CONSUMER_PREFETCH='1',
                   CATALOG_CACHE_ENABLED='true', PURCHASE_ADMISSION_LIMIT_ENABLED='false')
    perf.RESULTS = directory
    perf.environment_snapshot()
    if args.mode == 'baseline':
        scenarios = [('hot-ample', 100), ('hot-scarce', 100), ('mixed', 25), ('same-user-cross-session', 25)]
    elif args.mode == 'steps':
        scenarios = [('hot-ample', vus) for vus in [25, 50, 100, 200]]
    elif args.mode == 'waitlist':
        scenarios = [('waitlist', 100)]
    else:
        scenarios = [('hot-ample', 25)]
    if args.reverse:
        scenarios.reverse()
    report = {'passed': False, 'startedAt': datetime.now(timezone.utc).isoformat(), 'rounds': [],
              'jarSha256': hashlib.sha256((perf.ROOT / args.jar).read_bytes()).hexdigest(),
              'scripts': {name: hashlib.sha256((perf.HERE / name).read_bytes()).hexdigest()
                          for name in ['perf.py', 'scenarios.js', 'final_load_suite.py']}}
    perf.save(directory / 'comparison.json', report)
    for scenario, vus in scenarios:
        mode = 'mixed' if scenario == 'same-user-cross-session' else scenario
        fixture = perf.prepare(mode, vus)
        if scenario == 'same-user-cross-session':
            fixture['users'] = [fixture['users'][0]] * vus
            fixture['sharedUserScenario'] = True
            perf.save(perf.PRIVATE / 'input.json', fixture)
        import final_jfr
        recording = final_jfr.begin(args.label + '-' + scenario + '-' + str(vus))
        path = directory / (scenario + '-' + str(vus)) / (mode + '-' + str(vus))
        try:
            perf.run(SimpleNamespace(label=scenario + '-' + str(vus), scenario=mode, vus=vus,
                     warmup=10, duration=args.steady_seconds, k6=None, activity_id=None, session_id=None,
                     sku_id=None, admission_rejections=False, live_monitor=True, preauthenticate=True))
        finally:
            final_jfr.finish(recording, path)
        environment = json.loads((path / 'environment.json').read_text(encoding='utf-8'))
        from metric_quantiles import snapshot
        perf.save(path / 'timer-quantiles.json', snapshot(perf.admin(), perf.BASE, perf.MONITOR))
        metrics = json.loads((path / 'k6-summary.json').read_text(encoding='utf-8'))['metrics']
        def rate(name):
            return metrics.get(name, {}).get('values', {}).get('count', 0) / args.steady_seconds
        def quantiles(name):
            values = metrics.get(name, {}).get('values', {})
            return {key: values.get(key) for key in ['med', 'p(95)', 'p(99)', 'max']}
        row = {'scenario': scenario, 'vus': vus, 'independentBuyerAccounts': 1 if fixture.get('sharedUserScenario') else vus,
               'steadySeconds': args.steady_seconds, 'httpRps': rate('steady_requests'),
               'acceptedTps': rate('accepted_purchase_requests'), 'successfulOrderTps': rate('created_orders'),
               'paidTps': rate('steady_paid_at_completion'),
               'successCompletionMs': quantiles('steady_purchase_success_latency'),
               'businessFailureCompletionMs': quantiles('steady_purchase_business_failure_latency'),
               'queueExpiredCompletionMs': quantiles('steady_purchase_queue_expired_latency'),
               'legacyCompletionMs': quantiles('steady_purchase_completion_latency'),
               'k6ExitCode': environment['k6ExitCode'], 'mysqlDelta': environment['mysqlDelta'],
               'auditPassed': environment['audit']['passed'], 'raw': path.relative_to(perf.ROOT).as_posix()}
        report['rounds'].append(row)
        perf.save(directory / 'comparison.json', report)
        # 200VU若发生错误/持久积压则后续负载停止，禁止通过扩大并发掩盖边界。
        if row['mysqlDelta'].get('lock_deadlocks', 0) or row['mysqlDelta'].get('lock_timeouts', 0):
            raise RuntimeError('出现数据库锁故障，停止加压并保留现场')
    report.update(passed=True, endedAt=datetime.now(timezone.utc).isoformat(),
                  measurement='HTTP/订单按完成时刻截取预热后固定窗口，成功与失败时延分开；默认SINGLE，Lua准入关闭。')
    perf.save(directory / 'comparison.json', report)


if __name__ == '__main__':
    main()
