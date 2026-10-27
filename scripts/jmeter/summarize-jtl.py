#!/usr/bin/env python3
"""按 POST 发起时间与幂等键关联终态，检查截尾并计算成功样本最近秩延迟。"""
from __future__ import annotations

import argparse
import csv
import json
import math
from collections import Counter
from pathlib import Path

SUBMIT = '购票申请受理'
POLL = '查询购票申请状态'
TRANSACTION = '订单创建端到端'


def summarize(path: Path, start: int | None, end: int | None):
    posts, transactions, terminals = [], {}, {}
    counts = Counter()
    with path.open(encoding='utf-8-sig', newline='') as stream:
        for row in csv.DictReader(stream):
            label, key = row.get('label'), row.get('idempotencyKey', '')
            timestamp = int(row['timeStamp'])
            if label == SUBMIT:
                if (start is None or start <= timestamp < end):
                    posts.append(row)
                elif end is not None and timestamp >= end:
                    counts['posts_after_window'] += 1
            elif label == TRANSACTION and key:
                if key in transactions:
                    counts['duplicate_transaction_keys'] += 1
                transactions[key] = row
            elif label == POLL and key and row.get('purchaseOutcome') in ('SUCCEEDED', 'FAILED', 'EXPIRED'):
                observed = timestamp + int(row['elapsed'])
                if key not in terminals or observed < terminals[key][0]:
                    terminals[key] = (observed, row)
    cohort = []
    seen = set()
    for post in posts:
        key = post.get('idempotencyKey', '')
        if not key:
            counts['missing_idempotency_key'] += 1
        if key in seen:
            counts['duplicate_post_keys'] += 1
        seen.add(key)
        tx, terminal = transactions.get(key), terminals.get(key)
        code = post.get('responseCode', '')
        request_id = post.get('requestId', '')
        if code == '202' and not request_id:
            counts['accepted_missing_request_id'] += 1
        if not tx:
            counts['missing_transaction'] += 1
        outcome = 'REJECTED' if code != '202' else (tx.get('purchaseOutcome', '') if tx else 'UNOBSERVED')
        if code == '202' and tx and not outcome:
            outcome = 'UNKNOWN'
        duration = ''
        observed = ''
        order_id = ''
        if terminal:
            observed, sample = terminal
            order_id = sample.get('orderId', '')
            if request_id and sample.get('requestId') != request_id:
                counts['request_id_mismatch'] += 1
            elif tx and tx.get('requestId') != request_id:
                counts['request_id_mismatch'] += 1
            elif tx and tx.get('orderId', '') != order_id and sample.get('purchaseOutcome') == 'SUCCEEDED':
                counts['order_id_mismatch'] += 1
            elif sample.get('purchaseOutcome') == 'SUCCEEDED' and order_id.isdigit():
                duration = observed - int(post['timeStamp'])
        if outcome == 'SUCCEEDED' and (duration == '' or duration < 0 or not order_id):
            counts['success_without_observation'] += 1
            outcome = 'INVALID_SUCCESS'
        if outcome == 'SUCCEEDED' and not request_id:
            outcome = 'INVALID_SUCCESS'
        counts['outcome_' + outcome] += 1
        counts['http_' + code] += 1
        cohort.append({'idempotencyKey': key, 'postStartMs': int(post['timeStamp']), 'httpCode': code,
                       'requestId': request_id, 'outcome': outcome, 'orderId': order_id,
                       'observedAtMs': observed, 'elapsedMs': duration,
                       'failureCode': (tx or post).get('failureCode', ''), 'hasTransaction': bool(tx)})
    durations = sorted(row['elapsedMs'] for row in cohort if row['outcome'] == 'SUCCEEDED')
    def percentile(p):
        return durations[math.ceil(len(durations) * p) - 1] if durations else None
    result = {'windowStartMs': start, 'windowEndMs': end, 'posts': len(posts), 'counts': dict(sorted(counts.items())),
              'completedTransactionRate': (len(posts) - counts['missing_transaction']) / len(posts) if posts else None,
              'successfulSamples': len(durations), 'p50Ms': percentile(.50), 'p95Ms': percentile(.95), 'p99Ms': percentile(.99),
              'complete': not any(counts[k] for k in ('missing_transaction', 'accepted_missing_request_id',
                                                      'missing_idempotency_key', 'duplicate_post_keys',
                                                      'duplicate_transaction_keys', 'success_without_observation',
                                                      'request_id_mismatch', 'order_id_mismatch'))}
    return result, cohort


def main():
    parser = argparse.ArgumentParser(description='按 POST 发起时间统计终态和成功样本 P99')
    parser.add_argument('jtl', type=Path)
    parser.add_argument('--window-start-ms', type=int)
    parser.add_argument('--window-end-ms', type=int)
    parser.add_argument('--poll-interval-ms', type=int, default=250)
    parser.add_argument('--json', type=Path)
    parser.add_argument('--cohort-csv', type=Path)
    args = parser.parse_args()
    if (args.window_start_ms is None) != (args.window_end_ms is None):
        parser.error('窗口起止必须同时指定')
    if args.window_start_ms is not None and args.window_start_ms >= args.window_end_ms:
        parser.error('窗口结束必须晚于开始')
    summary, cohort = summarize(args.jtl, args.window_start_ms, args.window_end_ms)
    summary['pollIntervalMs'] = args.poll_interval_ms
    output = json.dumps(summary, ensure_ascii=False, indent=2)
    print(output)
    if args.json:
        args.json.write_text(output + '\n', encoding='utf8')
    if args.cohort_csv:
        with args.cohort_csv.open('w', encoding='utf8', newline='') as stream:
            fields = ['idempotencyKey','postStartMs','httpCode','requestId','outcome','orderId','observedAtMs','elapsedMs','failureCode','hasTransaction']
            writer = csv.DictWriter(stream, fieldnames=fields)
            writer.writeheader()
            writer.writerows(cohort)
    if not summary['complete']:
        print('警告：存在未完整映射或截尾；成功样本 P99 不能代表完整窗口。')


if __name__ == '__main__':
    main()
