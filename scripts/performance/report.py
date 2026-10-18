"""从 k6 原始计数计算稳定窗口吞吐，不误用包含预热的 Counter.rate。"""
import json
from pathlib import Path
from perf import RESULTS


def measurements(folder):
    meta = json.loads((folder/'environment.json').read_text(encoding='utf-8'))
    metrics = json.loads((folder/'k6-summary.json').read_text(encoding='utf-8'))['metrics']
    def values(name): return metrics.get(name, {}).get('values', {})
    seconds = meta['steadySeconds']
    lat = values('steady_latency')
    return {'scenario':meta['scenario'], 'vus':meta['vus'], 'requests':values('steady_requests').get('count',0),
            'rps':round(values('steady_requests').get('count',0)/seconds,2),
            'createTps':round(values('created_orders').get('count',0)/seconds,2),
            'paidSuccesses':values('paid_orders').get('count',0),
            'p50Ms':round(lat.get('med',0),2), 'p95Ms':round(lat.get('p(95)',0),2), 'p99Ms':round(lat.get('p(99)',0),2),
            'unexpectedRate':values('unexpected_error').get('rate'),
            'expectedConflicts':values('expected_conflicts').get('count',0),
            'lockWaitMs':meta['mysqlDelta'].get('Innodb_row_lock_time'),
            'lockWaitCount':meta['mysqlDelta'].get('Innodb_row_lock_waits'),
            'auditPassed':meta['audit']['passed'], 'k6Passed':meta['k6ExitCode']==0,
            'outbox':meta['final']['outbox'], 'outboxLag':meta['final']['outboxLag']}


if __name__=='__main__':
    data = {}
    for label in ['baseline','optimized']:
        data[label] = sorted([measurements(p.parent) for p in (RESULTS/label).glob('*/environment.json')],key=lambda d:(d['scenario'],d['vus']))
    (RESULTS/'comparison.json').write_text(json.dumps(data,ensure_ascii=False,indent=2),encoding='utf-8')
    print('|版本|场景|并发|RPS|下单TPS|关联支付成功数|P50毫秒|P95毫秒|P99毫秒|预期409|非预期错误率|锁等待毫秒|审计|')
    print('|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|')
    for label, cases in data.items():
        for d in cases:
            print(f'|{label}|{d["scenario"]}|{d["vus"]}|{d["rps"]}|{d["createTps"]}|{d["paidSuccesses"]}|{d["p50Ms"]}|{d["p95Ms"]}|{d["p99Ms"]}|{d["expectedConflicts"]}|{d["unexpectedRate"]}|{d["lockWaitMs"]}|{d["auditPassed"]}|')
