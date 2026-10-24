"""仅在固定隔离库验证退款审计；所有反例在同连接事务内回滚，不提交业务修改。"""
import argparse
import re
import perf


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--label', required=True)
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,63}', args.label):
        raise RuntimeError('报告标签不合法')
    destination = perf.RESULTS / (args.label + '-refund-audit-probes.json')
    if destination.exists():
        raise RuntimeError('报告已存在，禁止覆盖')
    perf.connect().close()
    perf.app_stop()
    before = perf.audit()
    source = perf.rows("""SELECT o.id,o.user_id,i.sku_id,i.quantity FROM ticket_order o
        JOIN ticket_order_item i ON i.order_id=o.id WHERE o.status='REFUNDED'
        AND NOT EXISTS(SELECT 1 FROM waitlist_entry w WHERE w.reserved_order_id=o.id)
        ORDER BY o.id LIMIT 1""")
    if not source:
        raise RuntimeError('请先在隔离环境通过模拟退款生成完整退款夹具')
    order = source[0]
    statements = [statement.strip() for statement in '\n'.join(
        line for line in (perf.HERE / 'audit.sql').read_text(encoding='utf-8').splitlines()
        if not line.lstrip().startswith('--')).split(';') if statement.strip()]
    selected = [s for s in statements if any("SELECT '" + name + "'" in s
        for name in ['payment_snapshot', 'ticket_quantity', 'ticket_relation', 'waitlist_relation'])]
    results = []

    def check(name, changes, expected=None):
        with perf.connect() as db, db.cursor() as cur:
            db.begin()
            try:
                for sql, parameters in changes:
                    cur.execute(sql, parameters)
                    assert cur.rowcount > 0, '反例未修改夹具'
                checks = []
                for statement in selected:
                    cur.execute(statement)
                    checks.extend(cur.fetchall())
                violations = {row['invariant'] for row in checks if row['violation_count']}
                assert violations == (set() if expected is None else {expected}), (name, checks)
                results.append({'case': name, 'expectedViolation': expected, 'passed': True})
            finally:
                db.rollback()

    order_id = order['id']
    check('完整退款保留支付事实和作废票券', [])
    check('退款订单支付金额异常', [('UPDATE payment_record SET amount_cents=amount_cents+1 WHERE order_id=%s', (order_id,))], 'payment_snapshot')
    check('退款金额异常', [('UPDATE refund_record SET amount_cents=amount_cents+1 WHERE order_id=%s', (order_id,))], 'payment_snapshot')
    check('缺少退款流水', [('DELETE FROM refund_record WHERE order_id=%s', (order_id,))], 'payment_snapshot')
    check('缺少作废票券', [('DELETE FROM electronic_ticket WHERE order_id=%s ORDER BY id LIMIT 1', (order_id,))], 'ticket_quantity')
    check('退款票券仍可使用', [("UPDATE electronic_ticket SET status='UNUSED' WHERE order_id=%s ORDER BY id LIMIT 1", (order_id,))], 'ticket_relation')
    check('非退款订单存在退款流水', [("UPDATE ticket_order SET status='PAID' WHERE id=%s", (order_id,)),
        ("UPDATE electronic_ticket SET status='UNUSED' WHERE order_id=%s", (order_id,))], 'payment_snapshot')
    check('已支付订单存在作废票券', [('DELETE FROM refund_record WHERE order_id=%s', (order_id,)),
        ("UPDATE ticket_order SET status='PAID' WHERE id=%s", (order_id,))], 'ticket_relation')

    with perf.connect() as db, db.cursor() as cur:
        db.begin()
        try:
            cur.execute("""INSERT INTO waitlist_entry(user_id,sku_id,quantity,status,reserved_order_id,expires_at)
                VALUES(%s,%s,%s,'COMPLETED',%s,UTC_TIMESTAMP(6)+INTERVAL 1 DAY)""",
                (order['user_id'], order['sku_id'], order['quantity'], order_id))
            waitlist_id = cur.lastrowid
            cur.execute("UPDATE ticket_order SET idempotency_operation='WAITLIST_CONFIRM',idempotency_key=%s WHERE id=%s",
                ('waitlist-' + str(waitlist_id), order_id))
            relation = next(s for s in selected if "SELECT 'waitlist_relation'" in s)
            cur.execute(relation)
            assert cur.fetchone()['violation_count'] == 0
            results.append({'case': '退款保留COMPLETED候补历史', 'expectedViolation': None, 'passed': True})
            cur.execute('UPDATE waitlist_entry SET quantity=quantity+1 WHERE id=%s', (waitlist_id,))
            cur.execute(relation)
            assert cur.fetchone()['violation_count'] == 1
            results.append({'case': '退款候补数量关联异常', 'expectedViolation': 'waitlist_relation', 'passed': True})
        finally:
            db.rollback()
    after = perf.audit()
    assert before == after, '反例回滚后的完整审计与计数须一致'
    perf.save(destination, {'passed': True, 'environment': 'ticketing-perf-only',
        'mutationMode': '同连接未提交事务，finally回滚；应用已停',
        'checks': results, 'fullAuditBefore': before, 'fullAuditAfter': after})
    print(str(len(results)) + '项退款审计正反例通过，事务已回滚，完整审计一致')


if __name__ == '__main__':
    main()
