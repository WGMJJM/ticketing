#!/usr/bin/env python3
"""只读汇总诊断证据，不读取会话、口令或环境文件。"""
import argparse
from collections import Counter
import json
from pathlib import Path
from statistics import mean


def slope(points):
    if len(points) < 2:
        return None
    xs = [(x - points[0][0]) / 1000 for x, _ in points]
    ys = [y for _, y in points]
    mx, my = mean(xs), mean(ys)
    denominator = sum((x - mx) ** 2 for x in xs)
    return sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / denominator if denominator else None


def metric_key(row):
    return row['name'], tuple(sorted(row['tags'].items()))


def stock_ledger(sample):
    row = sample.get('stockLedger')
    return dict(zip(row[::2], row[1::2])) if isinstance(row, list) else row


def analyze(directory):
    summary = json.loads((directory / 'summary.json').read_text(encoding='utf-8'))
    run = json.loads((directory / 'run.json').read_text(encoding='utf-8'))
    samples = [json.loads(line) for line in (directory / 'resources.jsonl').read_text(encoding='utf-8').splitlines()]
    window = [s for s in samples if run['startMs'] <= s['timeMs'] < run['endMs']]
    metrics = json.loads((directory / 'metrics.json').read_text(encoding='utf-8'))
    before = {metric_key(m): m for m in metrics['before']['metrics']}
    timers = []
    for row in metrics['after']['metrics']:
        if 'count' not in row:
            continue
        old = before.get(metric_key(row), {})
        count = row['count'] - old.get('count', 0)
        total = row['sumMs'] - old.get('sumMs', 0)
        if count:
            timers.append(dict(name=row['name'], tags=row['tags'], count=count, totalMs=total,
                               meanMs=total/count, rollingPercentilesMs=row['percentilesMs']))
    waits = Counter(w['OBJECT_NAME'] for s in window for w in s['mysql']['waits'])
    wait_keys = Counter('|'.join(str(w.get(k)) for k in ('OBJECT_NAME','INDEX_NAME','LOCK_DATA'))
                        for s in window for w in s['mysql']['waits'])
    borrowers = Counter()
    for path in directory.glob('threads-*.txt'):
        for block in path.read_text(encoding='utf-8').split('\n\n'):
            if 'ConcurrentBag.borrow' in block:
                name = block.splitlines()[0]
                role = 'consumer' if 'purchaseOrders' in name else 'http' if 'http' in name else 'other'
                borrowers[role] += 1
    result = dict(directory=directory.name, summary=summary, run=run, windowSamples=len(window),
                  timers=timers, sampledLockWaitRows=dict(waits), sampledLockWaitKeys=dict(wait_keys), threadBorrowSnapshots=dict(borrowers))
    if window:
        result['resources'] = dict(cpuMean=mean(s['cpu'] for s in window), cpuMax=max(s['cpu'] for s in window),
            freeGiBMin=min(s['freeGiB'] for s in window),
            backendWorkingMiBMax=max(s['backend']['workingMiB'] for s in window),
            jmeterWorkingMiBMax=max(s.get('jmeter', {}).get('workingMiB', 0) for s in window),
            poolPendingMax=max(s['diagnostics']['pool']['pending'] for s in window),
            poolActiveMax=max(s['diagnostics']['pool']['active'] for s in window),
            readyMax=max(s['mq']['ready'] for s in window),
            unackedMax=max(s['mq']['unacked'] for s in window),
            ledgerNotReadySamples=sum(stock_ledger(s).get('ready') != '1' for s in window if stock_ledger(s) is not None)
                if any(stock_ledger(s) is not None for s in window) else None,
            readySlopePerSecond=slope([(s['timeMs'], s['mq']['ready']) for s in window]),
            mysqlThreadsRunningMax=max(s['mysql']['status']['Threads_running'] for s in window),
            sampleGapMax=max((b['timeMs']-a['timeMs'])/1000 for a,b in zip(samples,samples[1:])),
            rowLockWaitsDelta=window[-1]['mysql']['status']['Innodb_row_lock_waits']-window[0]['mysql']['status']['Innodb_row_lock_waits'],
            rowLockTimeMsDelta=window[-1]['mysql']['status']['Innodb_row_lock_time']-window[0]['mysql']['status']['Innodb_row_lock_time'],
            deadlocksDelta=(window[-1]['mysql']['status']['Innodb_deadlocks']-window[0]['mysql']['status']['Innodb_deadlocks'])
                if 'Innodb_deadlocks' in window[0]['mysql']['status'] else None)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    args = parser.parse_args()
    result = [analyze(path.parent) for path in sorted(args.directory.glob('c*/summary.json'))]
    target = args.directory / 'analysis.json'
    target.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    for r in result:
        s = r['summary']
        print(r['directory'], 'valid', s['valid'], 'TPS', round(s['orderTps'], 3),
              'HTTP P99', s['httpMs']['p99'], 'server P99', round(s['serverQueueAndCreateMs']['p99'] or 0, 2),
              'resources', json.dumps(r.get('resources'), ensure_ascii=False))
    print('完整汇总', target)


if __name__ == '__main__':
    main()
