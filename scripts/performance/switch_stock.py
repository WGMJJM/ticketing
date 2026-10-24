"""固定隔离环境的显式离线库存切换；不在启动时运行，不支持静默降回SINGLE。"""
import argparse
import gzip
import hashlib
from pathlib import Path
import re
import perf


def switch(db, sku_id, bucket_count, operator_id, backup_sha256, before_activate=None):
    """整个迁移为一个事务；回调仅供隔离故障验证，在策略改变前模拟进程中断。"""
    if not 1 <= bucket_count <= 16 or not re.fullmatch(r'[0-9a-f]{64}', backup_sha256):
        raise RuntimeError('分桶数量或备份摘要无效')
    with db.cursor() as cur:
        cur.execute('SELECT token FROM performance_control.environment_guard')
        if cur.fetchone()['token'] != perf.config()['guardToken']:
            raise RuntimeError('隔离guard不匹配，禁止切换')
        cur.execute('SELECT DATABASE() name')
        schema = cur.fetchone()['name']
        if schema != 'ticketing_perf' and not re.fullmatch(r'ticketing_s17_[a-f0-9]+_it', schema):
            raise RuntimeError('只允许固定性能库或本阶段新建隔离schema')
        cur.execute('SET TRANSACTION ISOLATION LEVEL READ COMMITTED')
        db.begin()
        try:
            cur.execute('''SELECT DISTINCT o.user_id FROM ticket_order o JOIN ticket_order_item i ON i.order_id=o.id
                WHERE i.sku_id=%s AND o.status IN('PENDING','PAID') LIMIT 100001''', (sku_id,))
            owners = {row['user_id'] for row in cur.fetchall()}
            if len(owners) > 100000:
                raise RuntimeError('历史订单范围超出维护预算，停止切换')
            for user in sorted(owners | {operator_id}):
                cur.execute('SELECT id,status,role FROM user_account WHERE id=%s FOR SHARE', (user,))
                account = cur.fetchone()
                if not account or (user == operator_id and (account['status'], account['role']) != ('ACTIVE', 'ADMIN')):
                    raise RuntimeError('操作账号无效或不是有效管理员')
            cur.execute('''SELECT s.id,s.activity_id FROM ticket_sku t
                JOIN activity_session s ON s.id=t.session_id WHERE t.id=%s''', (sku_id,))
            parent = cur.fetchone()
            if not parent:
                raise RuntimeError('票档不存在')
            cur.execute('SELECT id FROM activity WHERE id=%s FOR SHARE', (parent['activity_id'],))
            cur.execute('SELECT id FROM activity_session WHERE id=%s FOR UPDATE', (parent['id'],))
            cur.execute('SELECT * FROM ticket_sku WHERE id=%s FOR UPDATE', (sku_id,))
            sku = cur.fetchone()
            if sku['inventory_strategy'] == 'BUCKETED':
                cur.execute('SELECT bucket_count FROM stock_strategy_switch WHERE sku_id=%s', (sku_id,))
                receipt = cur.fetchone()
                if sku['bucket_count'] != bucket_count or not receipt or receipt['bucket_count'] != bucket_count:
                    raise RuntimeError('已启用分桶，禁止重新分配或无审计凭据重试')
                db.commit()
                return {'changed': False, 'bucketCount': bucket_count}
            cur.execute('SELECT COUNT(*) n FROM ticket_stock_bucket WHERE sku_id=%s', (sku_id,))
            if cur.fetchone()['n']:
                raise RuntimeError('SINGLE存在额外桶，停止并保留现场')
            cur.execute('''SELECT o.id,o.user_id,o.status,o.session_id,i.quantity,i.session_id item_session
                FROM ticket_order o JOIN ticket_order_item i ON i.order_id=o.id
                WHERE i.sku_id=%s AND o.status IN('PENDING','PAID') ORDER BY o.id LIMIT 100001''', (sku_id,))
            orders = cur.fetchall()
            if len(orders) > 100000 or any(o['user_id'] not in owners or o['quantity'] <= 0
                    or o['session_id'] != sku['session_id'] or o['item_session'] != sku['session_id'] for o in orders):
                raise RuntimeError('历史订单归属改变或异常，停止切换')
            locked = sum(o['quantity'] for o in orders if o['status'] == 'PENDING')
            sold = sum(o['quantity'] for o in orders if o['status'] == 'PAID')
            if locked != sku['locked_stock'] or sold != sku['sold_stock'] or locked + sold > sku['total_stock']:
                raise RuntimeError('历史库存与PENDING/PAID双向对账失败，停止切换')
            totals = [sku['total_stock'] // bucket_count + (number < sku['total_stock'] % bucket_count)
                      for number in range(bucket_count)]
            used = [0] * bucket_count
            bucket_locked = [0] * bucket_count
            bucket_sold = [0] * bucket_count
            allocations = []
            number = 0
            for order in orders:
                remaining = order['quantity']
                while remaining:
                    while number < bucket_count and used[number] == totals[number]:
                        number += 1
                    if number == bucket_count:
                        raise RuntimeError('历史分配超出总量，停止切换')
                    take = min(remaining, totals[number] - used[number])
                    allocations.append((order['id'], sku_id, number, take))
                    used[number] += take
                    (bucket_locked if order['status'] == 'PENDING' else bucket_sold)[number] += take
                    remaining -= take
            cur.executemany('INSERT INTO ticket_stock_bucket(sku_id,bucket_no,total_stock,locked_stock,sold_stock) VALUES(%s,%s,%s,%s,%s)',
                [(sku_id, n, totals[n], bucket_locked[n], bucket_sold[n]) for n in range(bucket_count)])
            if allocations:
                cur.executemany('INSERT INTO order_stock_allocation(order_id,sku_id,bucket_no,quantity) VALUES(%s,%s,%s,%s)', allocations)
            if before_activate:
                before_activate()
            cur.execute('''INSERT INTO stock_strategy_switch(sku_id,bucket_count,operator_id,backup_sha256,previous_locked,previous_sold)
                VALUES(%s,%s,%s,%s,%s,%s)''', (sku_id, bucket_count, operator_id, backup_sha256, locked, sold))
            cur.execute('''UPDATE ticket_sku SET inventory_strategy='BUCKETED',bucket_count=%s,
                locked_stock=0,sold_stock=0,version=version+1 WHERE id=%s AND inventory_strategy='SINGLE' ''', (bucket_count, sku_id))
            if cur.rowcount != 1:
                raise RuntimeError('策略最终条件更新失败，全部回滚')
            db.commit()
            return {'changed': True, 'bucketCount': bucket_count, 'pendingQuantity': locked,
                    'paidQuantity': sold, 'allocationRows': len(allocations)}
        except BaseException:
            db.rollback()
            raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sku-id', type=int, required=True)
    parser.add_argument('--buckets', type=int, required=True)
    parser.add_argument('--operator-id', type=int, required=True)
    parser.add_argument('--backup', required=True)
    parser.add_argument('--backup-sha256', required=True)
    parser.add_argument('--writes-stopped', action='store_true', required=True)
    parser.add_argument('--approve', action='store_true', required=True)
    args = parser.parse_args()
    backup = Path(args.backup).resolve()
    if not backup.is_relative_to((perf.ROOT / '.local').resolve()) or not backup.is_file():
        raise RuntimeError('须使用忽略目录内的已存在隔离备份')
    data = backup.read_bytes()
    if hashlib.sha256(data).hexdigest() != args.backup_sha256:
        raise RuntimeError('备份文件摘要不符，禁止切换')
    if b'Dump completed' not in (gzip.decompress(data) if backup.suffix == '.gz' else data):
        raise RuntimeError('备份缺少完整结束标记')
    perf.connect().close()
    perf.app_stop()
    perf.audit()
    with perf.connect() as db:
        result = switch(db, args.sku_id, args.buckets, args.operator_id, args.backup_sha256)
    perf.audit()
    print('离线分桶切换完成 ' + str(result))


if __name__ == '__main__':
    main()
