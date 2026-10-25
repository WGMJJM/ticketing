"""Guarded finite batches: distinct real sessions compete for 100 tickets through HTTP/Outbox/RabbitMQ."""
import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import threading
import time

import requests
import perf
from chain_metrics import distribution, snapshot as chain_snapshot

BUSINESS_CODES = {'TICKET_UNAVAILABLE', 'STOCK_UNAVAILABLE', 'TICKET_NOT_FOUND',
                  'SESSION_NOT_FOUND', 'PURCHASE_LIMIT', 'ACCOUNT_UNAVAILABLE', 'ORDER_AMOUNT_INVALID'}
STOCK_CODES = {'TICKET_UNAVAILABLE', 'STOCK_UNAVAILABLE'}
NONTERMINAL = {'QUEUED', 'PROCESSING'}


def validate_options(label, buyers, workers):
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,59}', label):
        raise RuntimeError('Invalid report label')
    if buyers not in (20, 50, 1000) or not 1 <= workers <= 128:
        raise RuntimeError('Only 20/50 smoke buyers or 1000 buyers, with 1..128 workers')
    if (perf.RESULTS / label).exists() or (perf.PRIVATE / label).exists():
        raise RuntimeError('Existing experiment label must never be overwritten')


@contextmanager
def exclusive_run():
    lock = perf.PRIVATE / 'limited-stock.lock'
    descriptor = os.open(lock, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    try:
        os.write(descriptor, str(os.getpid()).encode('ascii'))
        yield
    finally:
        os.close(descriptor)
        lock.unlink()


def guard_process():
    perf.connect().close()
    if perf.BASE != 'http://127.0.0.1:18080':
        raise RuntimeError('Only the fixed performance application is allowed')
    # Validate the sole listener as well as the recorded PID before any reset or restart.
    pidfile = perf.PRIVATE / 'app.pid'
    pid = int(pidfile.read_text()) if pidfile.exists() else None
    if os.name == 'nt':
        command = ("$items=@(Get-NetTCPConnection -State Listen -LocalPort 18080 -ErrorAction SilentlyContinue);"
                   "$items | Select-Object -ExpandProperty OwningProcess -Unique | ConvertTo-Json -Compress")
        output = subprocess.check_output(['powershell', '-NoProfile', '-Command', command], text=True).strip()
        owners = json.loads(output) if output else []
        owners = owners if isinstance(owners, list) else [owners]
        if owners and owners != [pid]:
            raise RuntimeError('Performance port belongs to an untracked process')
        if pid:
            command = (f"$p=Get-CimInstance Win32_Process -Filter 'ProcessId={pid}';"
                       "if (!$p -or !$p.CommandLine.Contains('scripts/performance/application-performance.yml'))"
                       " { throw 'Performance PID ownership mismatch' }")
            subprocess.run(['powershell', '-NoProfile', '-Command', command], check=True, capture_output=True)
    elif pid:
        if b'application-performance.yml' not in Path(f'/proc/{pid}/cmdline').read_bytes():
            raise RuntimeError('Performance PID ownership mismatch')


def require_drained():
    guard_process()
    if not perf.rows("SELECT COUNT(*) n FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='purchase_request'")[0]['n']:
        return
    for _ in range(15):
        active = perf.rows("SELECT COUNT(*) n FROM purchase_request WHERE status IN('QUEUED','PROCESSING')")[0]['n']
        budget = perf.rows('SELECT pending_count FROM purchase_capacity WHERE id=1')[0]['pending_count']
        events = perf.rows("SELECT COUNT(*) n FROM outbox_event WHERE status<>'SENT'")[0]['n']
        queues = perf.mq_snapshot()
        messages = sum((q.get('messages_ready') or 0) + (q.get('messages_unacknowledged') or 0) for q in queues)
        if active == budget == events == messages == 0:
            perf.audit()
            return
        time.sleep(1)
    raise RuntimeError('Prior requests, budget, Outbox or Rabbit messages have not drained; preserve scene')


def settings(scenario, paused):
    return {'PURCHASE_CAPACITY': '1000' if scenario == 'B' else '200',
            'PURCHASE_QUEUE_SECONDS': '900' if scenario == 'B' else '120',
            'PURCHASE_MESSAGING_ENABLED': str(not paused).lower(),
            'OUTBOX_JOBS_ENABLED': str(not paused).lower(),
            'PURCHASE_JOBS_ENABLED': 'true', 'PURCHASE_ADMISSION_LIMIT_ENABLED': 'false',
            'ORDER_PAYMENT_WINDOW_MINUTES': '15', 'WAITLIST_OUTBOX_IN_FLIGHT_WINDOW': '16',
            'PURCHASE_CONSUMER_CONCURRENCY': '2', 'PURCHASE_CONSUMER_PREFETCH': '1',
            'SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE': '10'}


def restart(jar, scenario, paused):
    guard_process()
    perf.app_stop()
    perf.app_start(jar, rate_limit_mode='regression', **settings(scenario, paused))


def audit(sku):
    checks = []
    with perf.connect() as db, db.cursor() as cur:
        cur.execute('SET TRANSACTION ISOLATION LEVEL REPEATABLE READ')
        cur.execute('START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY')
        cur.execute('SET @limited_sku=%s', (sku,))
        for filename in ('audit.sql', 'audit-purchase.sql', 'audit-quota.sql', 'audit-buckets.sql', 'limited_stock_audit.sql'):
            sql = '\n'.join(line for line in (perf.HERE / filename).read_text(encoding='utf-8').splitlines()
                            if not line.lstrip().startswith('--'))
            if filename == 'audit.sql':
                sql = sql.replace('FROM ticket_sku', 'FROM ticket_stock_balance')
            for statement in sql.split(';'):
                if statement.strip():
                    cur.execute(statement)
                    checks.extend(cur.fetchall())
        cur.execute('SELECT total_stock,locked_stock,sold_stock,inventory_strategy FROM ticket_stock_balance WHERE id=%s', (sku,))
        stock = cur.fetchone()
        cur.execute("SELECT o.status,COUNT(*) orders,SUM(i.quantity) units FROM ticket_order o JOIN ticket_order_item i ON i.order_id=o.id WHERE i.sku_id=%s GROUP BY o.status", (sku,))
        orders = cur.fetchall()
        cur.execute("SELECT status,COALESCE(failure_code,'NONE') failure_code,COUNT(*) n FROM purchase_request WHERE sku_id=%s GROUP BY status,failure_code", (sku,))
        states = cur.fetchall()
        cur.execute('SELECT pending_count FROM purchase_capacity WHERE id=1')
        budget = cur.fetchone()['pending_count']
        cur.execute('SELECT COALESCE(SUM(occupied_quantity),0) n FROM user_session_quota')
        quota = cur.fetchone()['n']
        cur.execute("SELECT status,COUNT(*) n FROM outbox_event GROUP BY status")
        outbox = cur.fetchall()
        cur.execute('SELECT UTC_TIMESTAMP(6) frozen_at')
        frozen = cur.fetchone()['frozen_at']
        db.commit()
    return {'passed': all(row['violation_count'] == 0 for row in checks), 'checks': checks,
            'frozenAtUtc': frozen, 'stock': stock, 'orders': orders, 'requests': states,
            'pendingBudget': budget, 'quotaUnits': quota, 'outbox': outbox}


def prepare_fixture(args, output, transport):
    require_drained()
    restart(args.jar, args.scenario, args.scenario != 'C')
    guard_process()
    fixture = perf.prepare('hot-ample', 1)
    target = fixture['target']
    adm = perf.admin()
    ticket = adm.request('GET', f'/api/v1/admin/sessions/{target["sessionId"]}/tickets')[0]
    payload = {key: ticket[key] for key in ('name', 'priceCents', 'saleStartsAt', 'saleEndsAt', 'maxQuantityPerOrder', 'version')}
    payload['totalStock'] = 100
    guard_process()
    adm.request('PUT', f'/api/v1/admin/tickets/{target["skuId"]}', payload)
    public = adm.request('GET', f'/api/v1/activities/{target["activityId"]}/sessions/{target["sessionId"]}/tickets')[0]
    if (public['totalStock'], public['lockedStock'], public['soldStock'], public['availableStock'], public['saleStatus']) != (100, 0, 0, 100, 'SELLING'):
        raise RuntimeError('Public inventory or sale window is invalid')
    seed = fixture['users'][0]
    password_hash = perf.rows('SELECT password_hash FROM user_account WHERE username=%s', (seed['username'],))[0]['password_hash']
    prefix = 'ls_' + secrets.token_hex(5)
    accounts = [{'username': f'{prefix}_{index}', 'password': seed['password']} for index in range(args.buyers)]
    guard_process()
    with perf.connect() as db, db.cursor() as cur:
        if perf.rows('SELECT COUNT(*) n FROM ticket_order')[0]['n']:
            raise RuntimeError('Accounts can only be prepared before any order exists')
        cur.execute("DELETE FROM user_account WHERE role='USER'")
        cur.executemany('INSERT INTO user_account(username,password_hash) VALUES(%s,%s)',
                        [(account['username'], password_hash) for account in accounts])
        cur.execute('SELECT id,username,role,status FROM user_account WHERE username LIKE %s ORDER BY id', (prefix + r'\_%',))
        buyers = cur.fetchall()
    ids = {buyer['id'] for buyer in buyers}
    total_users = perf.rows("SELECT COUNT(*) n FROM user_account WHERE role='USER'")[0]['n']
    if total_users != args.buyers or len(ids) != args.buyers or len({a['username'] for a in accounts}) != args.buyers or any(b['role'] != 'USER' or b['status'] != 'ACTIVE' for b in buyers):
        raise RuntimeError('Buyer identity uniqueness/state failed')
    id_by_name = {buyer['username']: buyer['id'] for buyer in buyers}
    def login(account):
        session = requests.Session()
        session.mount('http://', transport)
        session.trust_env = False
        return perf.Client(account, session=session)
    login_started = time.monotonic()
    with ThreadPoolExecutor(max_workers=8) as pool:
        clients = list(pool.map(login, accounts))
    metadata = {'accounts': args.buyers, 'totalUserAccounts': total_users, 'distinctUserIds': len(ids),
                'userIdsSha256': hashlib.sha256(','.join(map(str, sorted(ids))).encode()).hexdigest(),
                'target': target, 'publicTicket': public, 'label': args.label,
                'loginWorkers': 8, 'loginFailures': 0, 'loginSeconds': time.monotonic() - login_started,
                'submitWorkers': args.workers, 'maximumSharedConnections': max(64, args.workers),
                'quantityPerBuyer': 1, 'rateLimitMode': 'regression',
                'rateLimitEffectiveLimits': {key: value for key, value in perf.rate_limit_environment('regression').items() if 'SECRET' not in key},
                'overrides': settings(args.scenario, args.scenario != 'C'),
                'initialAudit': audit(target['skuId'])}
    if not metadata['initialAudit']['passed'] or metadata['initialAudit']['stock']['inventory_strategy'] != 'SINGLE':
        raise RuntimeError('Initial audit failed')
    perf.save(output / 'fixture-metadata.json', metadata)
    # Credentials and per-user mapping stay in the ignored private directory.
    perf.save(perf.PRIVATE / args.label / 'fixture.json', {'accounts': accounts, 'userIds': id_by_name, 'target': target})
    return fixture, accounts, clients, id_by_name, metadata


class Monitor:
    def __init__(self, samples, failures, epoch, phase):
        self.samples, self.failures, self.epoch, self.phase = samples, failures, epoch, phase
        self.stop = threading.Event()
        self.client = perf.admin()
        self.thread = threading.Thread(target=self.run, daemon=True)

    def run(self):
        while not self.stop.is_set():
            try:
                value = perf.sample(self.client)
                value.update(batchSeconds=time.monotonic() - self.epoch, phase=self.phase)
                self.samples.append(value)
            except Exception as error:
                self.failures.append({'phase': self.phase, 'errorType': type(error).__name__})
            self.stop.wait(2)

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *_):
        self.stop.set()
        self.thread.join(45)
        if self.thread.is_alive():
            raise RuntimeError('Monitoring did not stop within its bound')


def classification(status, code, request_id):
    if status == 202 and request_id:
        return 'accepted202'
    if status == 503 and code == 'PURCHASE_QUEUE_FULL':
        return 'admissionRejected'
    if status == 409 and code in BUSINESS_CODES:
        return 'knownPrecheckRejected'
    if status == 0 or status in (401, 403) or status >= 500:
        return 'transportFailures'
    return 'otherUnexpected'


def submit_batch(args, clients, sku, epoch):
    gate = threading.Barrier(min(args.workers, len(clients)) + 1)
    def submit(index):
        if index < min(args.workers, len(clients)):
            gate.wait(timeout=30)
        begin = time.monotonic()
        key = args.label + '-' + str(index)
        record = {'ordinal': index, 'startedSeconds': begin - epoch, 'key': key}
        try:
            response = clients[index].session.post(perf.BASE + '/api/v1/orders',
                json={'skuId': sku, 'quantity': 1},
                headers={**clients[index].token, 'Idempotency-Key': key}, timeout=30)
            record['status'] = response.status_code
            body = response.json()
            code = body.get('code')
            record['code'] = code if isinstance(code, str) and re.fullmatch(r'[A-Z0-9_]{1,80}', code) else None
            data = body.get('data') or {}
            record['requestId'] = data.get('requestId') if response.status_code == 202 else None
        except Exception as failure:
            record.update(status=0, code=type(failure).__name__, requestId=None)
        record.update(latencyMs=(time.monotonic() - begin) * 1000, completedSeconds=time.monotonic() - epoch)
        record['category'] = classification(record['status'], record['code'], record['requestId'])
        return record
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        futures = [pool.submit(submit, index) for index in range(len(clients))]
        gate.wait(timeout=30)
        records = [future.result() for future in futures]
    return records


def poll_all(records, accounts, clients, sku, epoch, relogin):
    accepted = [row for row in records if row['category'] == 'accepted202']
    deadline = time.monotonic() + 240
    errors = []
    def poll(record):
        index = record['ordinal']
        try:
            if relogin and not record.get('relogged'):
                perf.Client(accounts[index], session=clients[index].session)
                clients[index].csrf()
                record['relogged'] = True
            value = clients[index].request('GET', '/api/v1/purchase-requests/' + str(record['requestId']))
            if str(value['requestId']) != str(record['requestId']) or str(value['skuId']) != str(sku) or value['quantity'] != 1:
                raise RuntimeError('Owner purchase HTTP projection mismatch')
            if value['status'] not in NONTERMINAL:
                record.update(terminalStatus=value['status'], failureCode=value['failureCode'], orderId=value['orderId'],
                              terminalObservedMs=(time.monotonic() - epoch - record['startedSeconds']) * 1000)
                if value['status'] == 'SUCCEEDED':
                    order = clients[index].request('GET', '/api/v1/orders/' + str(value['orderId']))
                    record['orderHttpValid'] = (str(order['id']) == str(value['orderId']) and str(order['skuId']) == str(sku)
                                               and order['quantity'] == 1 and order['status'] == 'PENDING')
        except Exception as failure:
            errors.append({'ordinal': index, 'errorType': type(failure).__name__})
    with ThreadPoolExecutor(max_workers=8) as pool:
        while time.monotonic() < deadline:
            pending = [row for row in accepted if 'terminalStatus' not in row]
            if not pending:
                break
            list(pool.map(poll, pending))
            time.sleep(.5)
    return errors


def reconcile(records, db_records, id_by_index):
    by_id = {str(row['id']): row for row in db_records}
    accepted = [row for row in records if row['category'] == 'accepted202']
    differences = abs(len(accepted) - len(db_records))
    seen = set()
    for record in accepted:
        key = str(record['requestId'])
        row = by_id.get(key)
        if key in seen or row is None:
            differences += 1
            continue
        seen.add(key)
        differences += int(row['user_id'] != id_by_index[record['ordinal']] or row['idempotency_key'] != record['key'])
        differences += int(row['status'] != record.get('terminalStatus') or str(row['order_id']) != str(record.get('orderId'))
                           or row['failure_code'] != record.get('failureCode'))
        if row['status'] == 'SUCCEEDED':
            differences += int(not record.get('orderHttpValid'))
    return differences


def public_record(record):
    return {key: value for key, value in record.items()
            if key not in ('key', 'requestId', 'orderId', 'relogged')}


def check_replay(records, clients, sku):
    record = next(row for row in records if row['category'] == 'accepted202')
    client = clients[record['ordinal']]
    before = perf.rows('SELECT (SELECT COUNT(*) FROM purchase_request) requests,(SELECT COUNT(*) FROM ticket_order) orders,(SELECT pending_count FROM purchase_capacity WHERE id=1) budget')[0]
    replay = client.request('POST', '/api/v1/orders', {'skuId': sku, 'quantity': 1},
                            {'Idempotency-Key': record['key']}, statuses=(200, 202))
    response = client.session.post(perf.BASE + '/api/v1/orders', json={'skuId': sku, 'quantity': 2},
        headers={**client.token, 'Idempotency-Key': record['key']}, timeout=30)
    after = perf.rows('SELECT (SELECT COUNT(*) FROM purchase_request) requests,(SELECT COUNT(*) FROM ticket_order) orders,(SELECT pending_count FROM purchase_capacity WHERE id=1) budget')[0]
    return {'separateFromPrimaryBatch': True,
            'sameKeySameRequest': str(replay['requestId']) == str(record['requestId']),
            'differentQuantityConflict': response.status_code == 409 and response.json()['code'] == 'IDEMPOTENCY_CONFLICT',
            'factsUnchanged': before == after}


def execute_run(args, output):
    report = {'passed': False, 'scenario': args.scenario, 'label': args.label,
              'gitHead': subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip(),
              'jarSha256': hashlib.sha256(Path(args.jar).read_bytes()).hexdigest(),
              'startedAtUtc': datetime.now(timezone.utc).isoformat(), 'environment': 'ticketing-perf-only'}
    report['runnerSourcesSha256'] = {name: hashlib.sha256((perf.HERE / name).read_bytes()).hexdigest()
        for name in ('limited_stock_1000.py', 'limited_stock_audit.sql', 'perf.py')}
    perf.save(output / 'report.json', report)
    perf.environment_snapshot(output / 'environment.json')
    transport = requests.adapters.HTTPAdapter(pool_connections=max(64, args.workers),
        pool_maxsize=max(64, args.workers), pool_block=True, max_retries=0)
    samples, monitor_failures = [], []
    try:
        fixture, accounts, clients, identities, metadata = prepare_fixture(args, output, transport)
        sku = fixture['target']['skuId']
        before = perf.db_snapshot()
        epoch = time.monotonic()
        with Monitor(samples, monitor_failures, epoch, 'submission'):
            records = submit_batch(args, clients, sku, epoch)
            submit_seconds = max(row['completedSeconds'] for row in records) - min(row['startedSeconds'] for row in records)
            perf.save(output / 'http-completions.json', [public_record(row) for row in records])
            perf.save(output / 'after-submission-audit.json', audit(sku))
            if args.scenario != 'C':
                paused = audit(sku)
                perf.save(output / 'paused-audit.json', paused)
                accepted = sum(row['category'] == 'accepted202' for row in records)
                commands = perf.rows("SELECT COUNT(*) n FROM outbox_event WHERE event_type='PURCHASE_REQUEST_ACCEPTED' AND status='PENDING'")[0]['n']
                if not paused['passed'] or paused['orders'] or paused['pendingBudget'] != accepted or paused['quotaUnits'] != 0 or commands != accepted or paused['stock']['locked_stock'] != 0:
                    raise RuntimeError('Paused-state invariant failed; preserve all facts')
            else:
                poll_errors = poll_all(records, accounts, clients, sku, epoch, False)
        restore_start = time.monotonic()
        if args.scenario != 'C':
            restart(args.jar, args.scenario, False)
            with Monitor(samples, monitor_failures, epoch, 'recovery'):
                poll_errors = poll_all(records, accounts, clients, sku, epoch, True)
        perf.save(output / 'http-completions.json', [public_record(row) for row in records])
        require_drained()
        recovery_seconds = time.monotonic() - restore_start
        replay_checks = check_replay(records, clients, sku)
        final_audit = audit(sku)
        perf.save(output / 'final-audit.json', final_audit)
        database = perf.rows('SELECT id,user_id,idempotency_key,status,order_id,failure_code,accepted_at,updated_at FROM purchase_request WHERE sku_id=%s', (sku,))
        differences = reconcile(records, database, {i: identities[account['username']] for i, account in enumerate(accounts)})
        categories = Counter(row['category'] for row in records)
        outcomes = Counter((row['status'], row['code']) for row in records)
        states = Counter(row['status'] for row in database)
        failures = Counter(row['failure_code'] for row in database if row['status'] == 'FAILED')
        succeeded = states['SUCCEEDED']
        unexpected_failures = sum(count for code, count in failures.items() if code not in STOCK_CODES)
        desired_accepted = min(args.buyers, 1000 if args.scenario == 'B' else 200)
        expected_successes = min(100, args.buyers)
        successful = [row for row in records if row.get('terminalStatus') == 'SUCCEEDED']
        after = perf.db_snapshot()
        report.update(attempted=len(records), categories=dict(categories), accepted202=categories['accepted202'],
            outcomes=[{'status': status, 'code': code, 'count': count} for (status, code), count in sorted(outcomes.items(), key=str)],
            terminalStates=dict(states), terminalFailureCodes=dict(failures), terminalSucceeded=succeeded,
            terminalBusinessFailed=sum(count for code, count in failures.items() if code in BUSINESS_CODES),
            terminalExpired=states['EXPIRED'], terminalDeliveryExhausted=failures['PURCHASE_COMMAND_DELIVERY_EXHAUSTED'],
            stillNonterminal=states['QUEUED'] + states['PROCESSING'], httpDatabaseDifferences=differences,
            firstEquationDifference=len(records) - sum(categories.values()),
            secondEquationDifference=categories['accepted202'] - sum(states.values()),
            submissionSeconds=submit_seconds, batchHttpRps=len(records) / submit_seconds,
            batchAcceptedRps=categories['accepted202'] / submit_seconds,
            httpMs=distribution(row['latencyMs'] for row in records),
            acceptedMs=distribution(row['latencyMs'] for row in records if row['category'] == 'accepted202'),
            capacityRejectedMs=distribution(row['latencyMs'] for row in records if row['category'] == 'admissionRejected'),
            successfulTerminalObservedMs=distribution(row['terminalObservedMs'] for row in successful),
            allTerminalObservedMs=distribution(row.get('terminalObservedMs') for row in records),
            restartAndDrainSeconds=recovery_seconds if args.scenario != 'C' else None,
            finalDrainAfterHttpPollingSeconds=recovery_seconds, wholeBatchAndDrainSeconds=time.monotonic() - epoch,
            replayChecks=replay_checks,
            preparation=metadata, finalAudit=final_audit, pollErrors=poll_errors,
            monitoringFailures=monitor_failures, mysqlDelta={key: after['mysql'][key] - value for key, value in before['mysql'].items()},
            chain=chain_snapshot(perf.connect),
            timingScope='Finite batch, no steady TPS. HTTP-observed terminal latency includes polling, restart and reauthentication where applicable. DB updated_at is a write timestamp, not transaction commit.',
            smoke=args.buyers != 1000)
        report['batchSuccessfulOrderRpsIncludingDrain'] = succeeded / report['wholeBatchAndDrainSeconds']
        order_times = perf.rows('SELECT MIN(created_at) first_at, MAX(created_at) last_at FROM ticket_order')
        if succeeded:
            first, last = order_times[0]['first_at'], order_times[0]['last_at']
            report['orderWriteSpanSeconds'] = (last - first).total_seconds()
        report['sampledPeakPending'] = max([row['purchaseCapacity'][0]['pending_count'] for row in samples if row.get('purchaseCapacity')] + [0])
        report['sampledPeakOutboxPending'] = max([sum(e['n'] for e in row['outbox'] if e['status'] in ('PENDING', 'PROCESSING')) for row in samples] + [0])
        report['passed'] = (final_audit['passed'] and differences == 0 and len(records) == args.buyers
            and categories['transportFailures'] == categories['otherUnexpected'] == 0
            and succeeded == expected_successes and unexpected_failures == 0
            and report['terminalBusinessFailed'] == categories['accepted202'] - succeeded
            and report['firstEquationDifference'] == report['secondEquationDifference'] == 0
            and all(row['code'] in STOCK_CODES for row in records if row['category'] == 'knownPrecheckRejected')
            and states['EXPIRED'] == states['QUEUED'] == states['PROCESSING'] == 0
            and final_audit['pendingBudget'] == 0 and final_audit['stock']['locked_stock'] == expected_successes
            and final_audit['stock']['sold_stock'] == 0 and final_audit['quotaUnits'] == expected_successes
            and all(replay_checks.values())
            and not monitor_failures and not poll_errors
            and (args.scenario == 'C' or categories['accepted202'] == desired_accepted))
        perf.save(output / 'http-completions.json', [public_record(row) for row in records])
        perf.save(output / 'monitoring.json', samples)
        diagnostics = perf.diagnostics()
        diagnostics.pop('planParameters', None)
        perf.save(output / 'diagnostics.json', diagnostics)
        perf.save(output / 'report.json', report)
        print(json.dumps({key: report[key] for key in ('label', 'passed', 'attempted', 'accepted202', 'terminalSucceeded', 'terminalStates', 'terminalFailureCodes', 'httpDatabaseDifferences')}, ensure_ascii=True), flush=True)
        if not report['passed']:
            raise RuntimeError('Experiment failed; preserve artifacts and stop further load')
    except Exception as failure:
        report.update(passed=False, errorType=type(failure).__name__)
        perf.save(output / 'monitoring.json', samples)
        perf.save(output / 'report.json', report)
        raise
    finally:
        transport.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', required=True)
    parser.add_argument('--label', required=True)
    parser.add_argument('--scenario', choices=('A', 'B', 'C'), required=True)
    parser.add_argument('--buyers', type=int, default=1000)
    parser.add_argument('--workers', type=int, default=64)
    args = parser.parse_args()
    validate_options(args.label, args.buyers, args.workers)
    with exclusive_run():
        output = perf.RESULTS / args.label
        output.mkdir(parents=True, exist_ok=False)
        (perf.PRIVATE / args.label).mkdir(exist_ok=False)
        execute_run(args, output)


if __name__ == '__main__':
    main()
