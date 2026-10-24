"""在固定ticketing-perf库验证停写、备份、历史双向对账和离线分桶切换。"""
import argparse
import gzip
import hashlib
import json
from datetime import datetime, timedelta, timezone
from pathlib import Path
import subprocess
import sys
import time
import uuid

import perf
import switch_stock


def stock_audit():
    sql = '\n'.join(line for line in (perf.ROOT / 'scripts' / 'audit-stock-buckets.sql').read_text(encoding='utf-8').splitlines()
                    if not line.lstrip().startswith('--'))
    checks = []
    with perf.connect() as db, db.cursor() as cur:
        cur.execute('SET TRANSACTION ISOLATION LEVEL REPEATABLE READ')
        cur.execute('START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY')
        for statement in sql.split(';'):
            normalized = statement.strip()
            if not normalized or normalized.upper().startswith(('SET TRANSACTION', 'START TRANSACTION', 'COMMIT')):
                continue
            cur.execute(normalized)
            checks.extend(cur.fetchall())
        db.commit()
    if any(row['violation_count'] != 0 for row in checks):
        raise RuntimeError('独立库存桶只读审计失败')
    return {'passed': True, 'checks': checks}


def verified_backup(label):
    container = json.loads(subprocess.check_output(['docker', 'inspect', 'ticketing-perf-mysql-1'], text=True))[0]
    assert container['Config']['Labels']['com.docker.compose.project'] == 'ticketing-perf'
    raw = subprocess.check_output([
        'docker', 'exec', 'ticketing-perf-mysql-1', 'sh', '-c',
        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -u root --single-transaction --skip-lock-tables --set-gtid-purged=OFF --routines --triggers --databases ticketing_perf'
    ], stderr=subprocess.PIPE)
    path = perf.PRIVATE / 'backups' / (label + '-before-stock-switch.sql.gz')
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists():
        raise RuntimeError('压缩快照已存在，禁止覆盖')
    data = gzip.compress(raw)
    if b'Dump completed' not in raw:
        raise RuntimeError('mysqldump未正常结束，禁止切换')
    path.write_bytes(data)
    if gzip.decompress(path.read_bytes()) != raw:
        raise RuntimeError('隔离快照解压复核失败，禁止切换')
    return path, hashlib.sha256(data).hexdigest(), len(raw)


def assert_clean_switch_state(sku):
    with perf.connect() as db, db.cursor() as cur:
        cur.execute('SELECT inventory_strategy,bucket_count,locked_stock,sold_stock FROM ticket_sku WHERE id=%s', (sku,))
        assert cur.fetchone() == {'inventory_strategy': 'SINGLE', 'bucket_count': 0, 'locked_stock': 1, 'sold_stock': 1}
        cur.execute('SELECT COUNT(*) n FROM ticket_stock_bucket WHERE sku_id=%s', (sku,))
        assert cur.fetchone()['n'] == 0
        cur.execute('SELECT COUNT(*) n FROM order_stock_allocation a JOIN ticket_order_item i ON i.order_id=a.order_id AND i.sku_id=a.sku_id WHERE i.sku_id=%s', (sku,))
        assert cur.fetchone()['n'] == 0


def wait_for_waitlist(entry_id, expected):
    for _ in range(120):
        rows = perf.rows('SELECT status FROM waitlist_entry WHERE id=%s', (entry_id,))
        if rows and rows[0]['status'] == expected:
            return
        time.sleep(0.25)
    raise RuntimeError('Rabbit候补状态未按有界时间恢复至' + expected)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', required=True)
    parser.add_argument('--label', required=True)
    args = parser.parse_args()
    if not args.label.startswith('stage17-switch-') or any(c not in 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_' for c in args.label):
        raise RuntimeError('结果标签必须使用stage17-switch前缀和安全字符')
    output = perf.RESULTS / args.label
    if output.exists():
        raise RuntimeError('结果目录已存在，禁止覆盖')
    output.mkdir(parents=True)
    perf.initialize()
    perf.connect().close()
    perf.app_stop()
    report = {'environment': 'ticketing-perf-only', 'label': args.label, 'passed': False, 'checks': []}
    try:
        perf.app_start(args.jar, rate_limit_mode='regression', CATALOG_CACHE_ENABLED='true', PURCHASE_ADMISSION_LIMIT_ENABLED='false')
        fixture = perf.prepare('mixed', 8)
        sku = int(fixture['target']['skuId'])
        users = fixture['users']
        first = perf.Client(users[0])
        pending = first.order(sku)
        paid = perf.Client(users[1])
        paid_order = paid.order(sku)
        paid.request('POST', f'/api/v1/orders/{paid_order["id"]}/pay')
        facts_before = perf.rows("SELECT id,status,paid_at FROM ticket_order WHERE id IN (%s,%s) ORDER BY id", (pending['id'], paid_order['id']))
        assert [row['status'] for row in facts_before] == ['PENDING', 'PAID']
        assert facts_before[1]['paid_at'] is not None
        report['checks'].append({'case': '旧SINGLE库含PENDING与PAID历史事实', 'passed': True,
                                 'statuses': [row['status'] for row in facts_before], 'paidAtPreserved': True})

        operator = perf.rows("SELECT id FROM user_account WHERE username='perf_admin'")[0]['id']
        perf.app_stop()
        # 先检查两方向、额度、订单、Outbox等既有完整性，再采集停写快照。
        preflight = perf.audit()
        stock_preflight = stock_audit()
        path, digest, raw_bytes = verified_backup(args.label)
        report['backup'] = {'path': str(path.relative_to(perf.ROOT)), 'sha256': digest,
                            'compressedBytes': path.stat().st_size, 'uncompressedBytes': raw_bytes,
                            'decompressionAndDumpCompletionVerified': True}
        report['preflightAudit'] = preflight
        report['stockBucketPreflight'] = stock_preflight

        # 反向库存故障只在本固定压测schema中短暂注入；证明切换检测不一致后完整回滚，随后恢复本测试注入值。
        with perf.connect() as db, db.cursor() as cur:
            cur.execute('UPDATE ticket_sku SET locked_stock=locked_stock+1 WHERE id=%s', (sku,))
        try:
            with perf.connect() as db:
                switch_stock.switch(db, sku, 4, operator, digest)
            raise AssertionError('不匹配的历史库存不应通过切换')
        except RuntimeError as error:
            if '双向对账失败' not in str(error):
                raise
        with perf.connect() as db, db.cursor() as cur:
            cur.execute('UPDATE ticket_sku SET locked_stock=locked_stock-1 WHERE id=%s', (sku,))
        assert_clean_switch_state(sku)
        report['checks'].append({'case': '不匹配历史计数拒绝且无部分桶写入', 'passed': True})

        try:
            with perf.connect() as db:
                switch_stock.switch(db, sku, 4, operator, digest,
                                    before_activate=lambda: (_ for _ in ()).throw(RuntimeError('隔离中断注入')))
            raise AssertionError('中断注入应终止切换')
        except RuntimeError as error:
            if str(error) != '隔离中断注入':
                raise
        assert_clean_switch_state(sku)
        report['checks'].append({'case': '切换提交前中断完整回滚且可重试', 'passed': True})

        command = [sys.executable, str(perf.HERE / 'switch_stock.py'), '--sku-id', str(sku), '--buckets', '4',
                   '--operator-id', str(operator), '--backup', str(path), '--backup-sha256', digest,
                   '--writes-stopped', '--approve']
        result = subprocess.run(command, cwd=perf.ROOT, capture_output=True, text=True, encoding='utf-8', errors='replace')
        if result.returncode != 0:
            raise RuntimeError('受控离线切换CLI失败；固定性能库现场保留')
        with perf.connect() as db, db.cursor() as cur:
            cur.execute('SELECT inventory_strategy,bucket_count,locked_stock,sold_stock FROM ticket_sku WHERE id=%s', (sku,))
            active = cur.fetchone()
            assert active == {'inventory_strategy': 'BUCKETED', 'bucket_count': 4, 'locked_stock': 0, 'sold_stock': 0}
            cur.execute('SELECT COUNT(*) n FROM ticket_stock_bucket WHERE sku_id=%s', (sku,))
            assert cur.fetchone()['n'] == 4
            cur.execute('SELECT SUM(quantity) n FROM order_stock_allocation WHERE sku_id=%s', (sku,))
            assert cur.fetchone()['n'] == 2
        repartitionBefore = {
            'buckets': perf.rows('SELECT bucket_no,total_stock,locked_stock,sold_stock FROM ticket_stock_bucket WHERE sku_id=%s ORDER BY bucket_no', (sku,)),
            'allocations': perf.rows('SELECT order_id,bucket_no,quantity FROM order_stock_allocation WHERE sku_id=%s ORDER BY order_id,bucket_no', (sku,)),
            'receipt': perf.rows('SELECT bucket_count,backup_sha256,previous_locked,previous_sold FROM stock_strategy_switch WHERE sku_id=%s', (sku,))}
        retry = subprocess.run(command, cwd=perf.ROOT, capture_output=True, text=True, encoding='utf-8', errors='replace')
        if retry.returncode != 0:
            raise RuntimeError('同配置重试既有切换失败；固定性能库现场已保留')
        repartitionAfter = {
            'buckets': perf.rows('SELECT bucket_no,total_stock,locked_stock,sold_stock FROM ticket_stock_bucket WHERE sku_id=%s ORDER BY bucket_no', (sku,)),
            'allocations': perf.rows('SELECT order_id,bucket_no,quantity FROM order_stock_allocation WHERE sku_id=%s ORDER BY order_id,bucket_no', (sku,)),
            'receipt': perf.rows('SELECT bucket_count,backup_sha256,previous_locked,previous_sold FROM stock_strategy_switch WHERE sku_id=%s', (sku,))}
        assert repartitionAfter == repartitionBefore
        after_switch = perf.audit()
        standalone_after_switch = stock_audit()
        assert perf.rows("SELECT id,status,paid_at FROM ticket_order WHERE id IN (%s,%s) ORDER BY id", (pending['id'], paid_order['id'])) == facts_before
        report['checks'].append({'case': 'CLI离线切换历史归属与票据库存总量审计', 'passed': after_switch['passed'],
                                 'bucketCount': 4, 'historicalAllocationQuantity': 2,
                                 'historicOrderFactsUnchanged': True, 'idempotencyKey': 'not-repartitioned',
                                 'sameConfigurationRetryDidNotRepartition': True,'cliExitCode': result.returncode,
                                 'retryCliExitCode': retry.returncode,
                                 'standaloneReadOnlyAuditPassed': standalone_after_switch['passed']})

        saved_app = json.loads((perf.PRIVATE / 'app.json').read_text(encoding='utf-8'))
        perf.app_start(args.jar, rate_limit_mode='regression', **saved_app.get('overrides', {}))
        live = perf.Client(users[2])
        created = live.order(sku, 2)
        live.request('POST', f'/api/v1/orders/{created["id"]}/pay')
        allocation = perf.rows('SELECT bucket_no,quantity FROM order_stock_allocation WHERE order_id=%s ORDER BY bucket_no', (created['id'],))
        assert sum(row['quantity'] for row in allocation) == 2 and len(allocation) >= 1
        cancelled = perf.Client(users[3]).order(sku, 1)
        perf.Client(users[3]).request('POST', f'/api/v1/orders/{cancelled["id"]}/cancel')
        admin_client = perf.admin()
        now = datetime.now(timezone.utc)
        session_start = perf.rows('SELECT starts_at FROM activity_session WHERE id=%s',
                                  (fixture['target']['sessionId'],))[0]['starts_at'].replace(tzinfo=timezone.utc)
        extra = admin_client.request('POST', f'/api/v1/admin/sessions/{fixture["target"]["sessionId"]}/tickets', {
            'name': '真实Rabbit分桶FIFO-' + uuid.uuid4().hex[:8], 'priceCents': '100', 'totalStock': 4,
            'saleStartsAt': (now - timedelta(minutes=30)).isoformat(),
            'saleEndsAt': (session_start - timedelta(minutes=5)).isoformat(), 'maxQuantityPerOrder': 4})
        admin_client.request('POST', f'/api/v1/admin/tickets/{extra["id"]}/publish', {'version': extra['version']})
        perf.app_stop()
        perf.audit()
        extra_backup, extra_digest, extra_raw = verified_backup(args.label + '-waitlist')
        extra_operator = perf.rows("SELECT id FROM user_account WHERE username='perf_admin'")[0]['id']
        waitlist_switch = subprocess.run([
            sys.executable, str(perf.HERE / 'switch_stock.py'), '--sku-id', str(extra['id']), '--buckets', '4',
            '--operator-id', str(extra_operator), '--backup', str(extra_backup), '--backup-sha256', extra_digest,
            '--writes-stopped', '--approve'], cwd=perf.ROOT, capture_output=True, text=True,
            encoding='utf-8', errors='replace')
        if waitlist_switch.returncode != 0:
            raise RuntimeError('候补分桶票档离线切换失败；固定性能库现场已保留')
        saved_app = json.loads((perf.PRIVATE / 'app.json').read_text(encoding='utf-8'))
        perf.app_start(args.jar, rate_limit_mode='regression', **saved_app.get('overrides', {}))
        holder = perf.Client(users[3])
        held_order = holder.order(extra['id'], 4)
        head = perf.Client(users[4])
        tail = perf.Client(users[5])
        head_entry = head.request('POST', '/api/v1/waitlist', {'skuId': str(extra['id']), 'quantity': 2})
        tail_entry = tail.request('POST', '/api/v1/waitlist', {'skuId': str(extra['id']), 'quantity': 2})
        holder.request('POST', f'/api/v1/orders/{held_order["id"]}/cancel')
        wait_for_waitlist(head_entry['id'], 'ALLOCATED')
        assert perf.rows('SELECT status FROM waitlist_entry WHERE id=%s', (tail_entry['id'],))[0]['status'] == 'WAITING'
        head_order = perf.rows('SELECT reserved_order_id FROM waitlist_entry WHERE id=%s', (head_entry['id'],))[0]['reserved_order_id']
        head_allocation = perf.rows('SELECT bucket_no,quantity FROM order_stock_allocation WHERE order_id=%s ORDER BY bucket_no', (head_order,))
        assert len(head_allocation) == 2 and sum(row['quantity'] for row in head_allocation) == 2
        head.request('POST', f'/api/v1/orders/{head_order}/cancel')
        wait_for_waitlist(head_entry['id'], 'EXPIRED')
        wait_for_waitlist(tail_entry['id'], 'ALLOCATED')
        tail_order = perf.rows('SELECT reserved_order_id FROM waitlist_entry WHERE id=%s', (tail_entry['id'],))[0]['reserved_order_id']
        tail_allocation = perf.rows('SELECT bucket_no,quantity FROM order_stock_allocation WHERE order_id=%s ORDER BY bucket_no', (tail_order,))
        assert len(tail_allocation) == 2 and sum(row['quantity'] for row in tail_allocation) == 2
        tail.request('POST', f'/api/v1/orders/{tail_order}/cancel')
        wait_for_waitlist(tail_entry['id'], 'EXPIRED')
        final_audit = perf.audit()
        assert final_audit['passed']
        final_stock_audit = stock_audit()
        report['checks'].append({'case': '切换后真实Rabbit购票支付与取消', 'passed': True,
                                 'crossBucketAllocation': allocation, 'finalFullAuditPassed': True,
                                 'standaloneReadOnlyAuditPassed': final_stock_audit['passed']})
        report['checks'].append({'case': '切换后真实Rabbit候补FIFO跨桶分配与取消恢复', 'passed': True,
                                 'skuId': extra['id'], 'headEntryId': head_entry['id'], 'tailEntryId': tail_entry['id'],
                                 'headAllocatedBeforeTail': True, 'headBucketAllocation': head_allocation,
                                 'tailBucketAllocation': tail_allocation, 'cancelReleasesMappedBuckets': True,
                                 'waitlistBackupSha256': extra_digest, 'waitlistBackupBytes': extra_raw,
                                 'finalFullAuditPassed': final_audit['passed']})
        report['passed'] = True
    except BaseException as error:
        report['failure'] = {'type': type(error).__name__, 'message': str(error)[:500]}
        raise
    finally:
        perf.app_stop()
        perf.save(output / 'switch-verification.json', report)
        print('离线切换验证结果 ' + str((output / 'switch-verification.json').relative_to(perf.ROOT)), flush=True)


if __name__ == '__main__':
    main()
