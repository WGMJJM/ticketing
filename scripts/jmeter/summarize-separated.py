#!/usr/bin/env python3
"""按 POST 窗口和数据库时间分别汇总真实受理与真实建单。"""
import argparse
import collections
import csv
import datetime as dt
import importlib.util
import json
import math
from pathlib import Path

import pymysql

ROOT = Path(__file__).resolve().parents[2]
RUNTIME = (ROOT / '.local' / 'jmeter').resolve()
spec = importlib.util.spec_from_file_location('prepare_sessions', Path(__file__).with_name('prepare-sessions.py'))
sessions = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sessions)


def percentile(values, p):
    values = sorted(values)
    return values[math.ceil(len(values) * p) - 1] if values else None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--jtl', required=True, type=Path)
    parser.add_argument('--start-ms', required=True, type=int)
    parser.add_argument('--end-ms', required=True, type=int)
    parser.add_argument('--batch', required=True)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    if not args.jtl.resolve().is_relative_to(RUNTIME) or not args.output.resolve().is_relative_to(RUNTIME) or args.end_ms <= args.start_ms:
        parser.error('仅允许隔离结果和有效窗口')
    sessions.check_isolation()
    env = sessions.data.read_env(RUNTIME / 'compose.env')
    with args.jtl.open(encoding='utf-8-sig', newline='') as f:
        all_rows = [r for r in csv.DictReader(f) if r['label'] == '购票 POST']
    rows = [r for r in all_rows if args.start_ms <= int(r['timeStamp']) < args.end_ms]
    accepted = [r for r in rows if r['responseCode'] == '202' and r['postOutcome'] == 'ACCEPTED' and r['requestId'].isdigit() and r['success'] == 'true']
    accepted_keys = {r['idempotencyKey'] for r in accepted}
    keys = [r['idempotencyKey'] for r in rows]
    if len(set(keys)) != len(keys) or any(not key.startswith('jmeter-' + args.batch + '-') for key in keys):
        raise RuntimeError('幂等键重复或批次不匹配')
    seconds = (args.end_ms - args.start_ms) / 1000
    per_second = collections.Counter((int(r['timeStamp']) - args.start_ms) // 1000 for r in rows)
    per_second_accepted = collections.Counter((int(r['timeStamp']) - args.start_ms) // 1000 for r in accepted)
    per_second_threads = collections.defaultdict(list)
    for row in rows:
        if row.get('grpThreads', '').isdigit():
            per_second_threads[(int(row['timeStamp']) - args.start_ms) // 1000].append(int(row['grpThreads']))
    events = [(int(r['timeStamp']), 1) for r in rows] + [(int(r['timeStamp']) + int(r['elapsed']), -1) for r in rows]
    active = peak = 0
    for _, delta in sorted(events):
        active += delta
        peak = max(peak, active)
    admission = {'windowStartMs': args.start_ms, 'windowEndMs': args.end_ms, 'windowSeconds': seconds,
                 'postCount': len(rows), 'postRps': len(rows)/seconds, 'accepted': len(accepted),
                 'acceptedRps': len(accepted)/seconds, 'http202P99Ms': percentile([int(r['elapsed']) for r in accepted], .99),
                 'acceptedRatio': len(accepted)/len(rows) if rows else None,
                 'http202P95Ms': percentile([int(r['elapsed']) for r in accepted], .95),
                 'responseCodes': dict(collections.Counter(r['responseCode'] for r in rows)),
                 'failureCodes': dict(collections.Counter(r['failureCode'] for r in rows if r.get('failureCode'))),
                 'postsAfterWindow': sum(int(r['timeStamp']) >= args.end_ms for r in all_rows),
                 'peakInFlightPosts': peak, 'perSecondPosts': [per_second[i] for i in range(math.ceil(seconds))],
                 'perSecondAccepted': [per_second_accepted[i] for i in range(math.ceil(seconds))],
                 'perSecondThreadsAtResponse': [{'min': min(per_second_threads[i]), 'max': max(per_second_threads[i])}
                     if per_second_threads[i] else None for i in range(math.ceil(seconds))]}
    start = dt.datetime.fromtimestamp(args.start_ms/1000, dt.timezone.utc).replace(tzinfo=None)
    end = dt.datetime.fromtimestamp(args.end_ms/1000, dt.timezone.utc).replace(tzinfo=None)
    prefix = 'jmeter-' + args.batch + '-'
    connection = pymysql.connect(host='127.0.0.1', port=13307, user=env['MYSQL_USER'], password=env['MYSQL_PASSWORD'], database='ticketingjmeter', cursorclass=pymysql.cursors.DictCursor)
    with connection.cursor() as cur:
        cur.execute("""SELECT p.id,p.idempotency_key,p.status,p.failure_code,p.user_id,p.sku_id,p.quantity,p.order_id,p.accepted_at,
                       o.created_at,o.user_id order_user_id,o.idempotency_key order_key,o.idempotency_operation order_op,
                       i.sku_id item_sku,i.quantity item_quantity
                FROM purchase_request p LEFT JOIN ticket_order o ON o.id=p.order_id
                LEFT JOIN ticket_order_item i ON i.order_id=o.id
                WHERE p.idempotency_operation='CREATE_ORDER' AND LEFT(p.idempotency_key,%s)=%s""", (len(prefix), prefix))
        db_rows = cur.fetchall()
        cur.execute("""SELECT COUNT(DISTINCT o.id) n FROM ticket_order o JOIN purchase_request p ON p.order_id=o.id
              JOIN ticket_order_item i ON i.order_id=o.id AND i.sku_id=p.sku_id
              WHERE o.idempotency_operation='CREATE_ORDER' AND LEFT(o.idempotency_key,%s)=%s
              AND p.status='SUCCEEDED' AND p.idempotency_key=o.idempotency_key
              AND o.created_at >= %s AND o.created_at < %s""", (len(prefix), prefix, start, end))
        window_orders = cur.fetchone()['n']
        cur.execute('SELECT total_stock,locked_stock,sold_stock FROM ticket_sku WHERE id=1')
        stock = cur.fetchone()
        cur.execute("""SELECT COUNT(*) n FROM (SELECT user_id,idempotency_operation,idempotency_key
                FROM ticket_order WHERE idempotency_operation='CREATE_ORDER' AND LEFT(idempotency_key,%s)=%s
                GROUP BY user_id,idempotency_operation,idempotency_key HAVING COUNT(*)>1) duplicates""", (len(prefix), prefix))
        duplicate_orders = cur.fetchone()['n']
        cur.execute("""SELECT COUNT(*) n FROM (SELECT order_id FROM purchase_request
                WHERE order_id IS NOT NULL GROUP BY order_id HAVING COUNT(*)>1) duplicates""")
        duplicate_request_orders = cur.fetchone()['n']
    connection.close()
    by_key = {r['idempotency_key']: r for r in db_rows}
    anomalies = len(db_rows) - len(by_key)
    durations = []
    outcomes = collections.Counter()
    cohort = []
    for row in rows:
        fact = by_key.get(row['idempotencyKey'])
        status = fact['status'] if fact else 'UNKNOWN'
        if row['idempotencyKey'] in accepted_keys:
            outcomes[status] += 1
        latency = None
        if fact and status == 'SUCCEEDED':
            if (str(fact['id']) != row['requestId'] and row['responseCode'] == '202') or fact['order_id'] is None or fact['order_user_id'] != fact['user_id'] or fact['order_key'] != row['idempotencyKey'] or fact['order_op'] != 'CREATE_ORDER' or fact['item_sku'] != fact['sku_id'] or fact['item_quantity'] != fact['quantity']:
                anomalies += 1
            if fact['created_at'] and row['idempotencyKey'] in accepted_keys:
                latency = (fact['created_at'] - fact['accepted_at']).total_seconds() * 1000
                if latency < 0:
                    anomalies += 1
                else:
                    durations.append(latency)
        cohort.append({'idempotencyKey': row['idempotencyKey'], 'postStartMs': row['timeStamp'], 'httpCode': row['responseCode'],
                       'requestId': row['requestId'], 'finalStatus': status, 'orderId': fact['order_id'] if fact else '',
                       'acceptedAtUtc': str(fact['accepted_at']) if fact else '', 'orderCreatedAtUtc': str(fact['created_at']) if fact and fact['created_at'] else '', 'acceptedToCreatedMs': latency})
    after = sum(bool(r['created_at'] and r['created_at'] >= end) for r in db_rows)
    orders = {'windowOrderCount': window_orders, 'windowOrderTps': window_orders/seconds, 'ordersAfterWindow': after,
              'acceptedCohortStatus': dict(outcomes), 'successfulLatencySamples': len(durations),
              'acceptedToCreatedP99Ms': percentile(durations, .99), 'acceptedToCreatedP95Ms': percentile(durations, .95),
              'acceptedToCreatedP50Ms': percentile(durations, .50), 'unknownAccepted': outcomes['UNKNOWN'],
              'associationAnomalies': anomalies, 'duplicateOrders': duplicate_orders,
              'duplicateRequestOrders': duplicate_request_orders, 'databaseStock': stock}
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output/'admission.json').write_text(json.dumps(admission, ensure_ascii=False, indent=2)+'\n', encoding='utf8')
    (args.output/'order-creation.json').write_text(json.dumps(orders, ensure_ascii=False, indent=2)+'\n', encoding='utf8')
    with (args.output/'cohort.csv').open('w', encoding='utf8', newline='') as f:
        writer = csv.DictWriter(f, fieldnames=['idempotencyKey','postStartMs','httpCode','requestId','finalStatus','orderId','acceptedAtUtc','orderCreatedAtUtc','acceptedToCreatedMs'])
        writer.writeheader(); writer.writerows(cohort)
    print(json.dumps({'admission': admission, 'orderCreation': orders}, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
