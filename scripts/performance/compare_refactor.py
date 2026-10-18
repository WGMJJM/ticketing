"""核对第九阶段指定场景，生成对照，不覆盖第八阶段原始报告。"""
import argparse
import json
import re

import perf
from report import measurements

CASES = ['query-100', 'hot-ample-100', 'mixed-50', 'waitlist-100', 'checkin-100']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--label', default='stage9')
    parser.add_argument('--repeat-label', action='append', default=[])
    args = parser.parse_args()
    if not all(re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,63}', value)
               for value in [args.label, *args.repeat_label]):
        raise RuntimeError('压测结果标签不正确')
    result = {'reference': '第八阶段optimized原始数据', 'currentLabel': args.label,
              'schemaChange': 'V5新增必要复合外键及两个引用索引，写入成本包含在复测中', 'cases': []}
    for name in CASES:
        folders = [perf.RESULTS/'optimized'/name, perf.RESULTS/args.label/name]
        metadata = [json.loads((folder/'environment.json').read_text(encoding='utf-8')) for folder in folders]
        for folder, meta in zip(folders, metadata):
            summary = json.loads((folder/'k6-summary.json').read_text(encoding='utf-8'))
            assert 'setup_data' not in summary and meta['k6ExitCode'] == 0 and meta['audit']['passed'] and not meta['monitorFailures']
            metrics = summary['metrics']
            assert metrics['unexpected_error']['values']['rate'] == 0
            assert metrics['http_req_failed']['values']['rate'] == 0
            assert metrics['server_errors']['values']['count'] == 0 and metrics['transport_errors']['values']['count'] == 0
            if name == 'checkin-100':
                assert metrics['checked_tickets']['values']['count'] == 1
            if name == 'waitlist-100':
                assert meta['audit']['counts']['payment_record'] == 100
        for key in ['data', 'warmupSeconds', 'steadySeconds', 'vus', 'scenario']:
            assert metadata[0][key] == metadata[1][key], '对照流量或数据规模不同'
        before, after = [measurements(folder) for folder in folders]
        result['cases'].append({'case': name, 'before': before, 'after': after,
                                'rpsChangePercent': round((after['rps']/before['rps']-1)*100, 2),
                                'p95ChangePercent': round((after['p95Ms']/before['p95Ms']-1)*100, 2),
                                'artifacts': [meta['app']['sha256'] for meta in metadata]})
    result['waitlistRepeats'] = []
    for label in args.repeat_label:
        folder = perf.RESULTS/label/'waitlist-100'
        meta = json.loads((folder/'environment.json').read_text(encoding='utf-8'))
        summary = json.loads((folder/'k6-summary.json').read_text(encoding='utf-8'))
        metrics = summary['metrics']
        assert meta['k6ExitCode'] == 0 and meta['audit']['passed'] and not meta['monitorFailures']
        assert meta['vus'] == 100 and meta['warmupSeconds'] == 10 and meta['steadySeconds'] == 20
        assert meta['audit']['counts']['payment_record'] == 100
        assert metrics['unexpected_error']['values']['rate'] == metrics['http_req_failed']['values']['rate'] == 0
        assert metrics['server_errors']['values']['count'] == metrics['transport_errors']['values']['count'] == 0
        assert 'setup_data' not in summary
        result['waitlistRepeats'].append({'label': label, **measurements(folder)})
    result['passed'] = True
    output = perf.ROOT/'docs/architecture/results'/f'performance-{args.label}.json'
    perf.save(output, result)
    print('| 场景 | RPS前/后 | 下单TPS前/后 | P95毫秒前/后 | P99毫秒前/后 | 累计锁等待毫秒前/后 | 审计 |')
    print('| --- | ---: | ---: | ---: | ---: | ---: | --- |')
    for row in result['cases']:
        before, after = row['before'], row['after']
        values = [f'{before[key]}/{after[key]}' for key in ['rps', 'createTps', 'p95Ms', 'p99Ms', 'lockWaitMs']]
        print('| ' + row['case'] + ' | ' + ' | '.join(values) + ' | 通过 |')


if __name__ == '__main__':
    main()
