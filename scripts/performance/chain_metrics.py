"""一致性快照下的完整链路时间分布；只输出汇总，保留所有通道未投递积压。"""
import math


def distribution(values):
    samples = sorted(float(value) for value in values if value is not None)
    if not samples:
        return {'count': 0, 'p50': None, 'p95': None, 'p99': None, 'max': None}
    def percentile(fraction):
        position = (len(samples) - 1) * fraction
        lower = math.floor(position)
        upper = math.ceil(position)
        return samples[lower] + (samples[upper] - samples[lower]) * (position - lower)
    return {'count': len(samples), 'p50': percentile(.5), 'p95': percentile(.95),
            'p99': percentile(.99), 'max': samples[-1]}


def snapshot(connect):
    with connect() as db, db.cursor() as cur:
        cur.execute('SET TRANSACTION ISOLATION LEVEL REPEATABLE READ')
        cur.execute('START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY')
        cur.execute("""SELECT event_type,status,retry_count,
            TIMESTAMPDIFF(MICROSECOND,created_at,sent_at)/1000 confirmed_ms,
            TIMESTAMPDIFF(MICROSECOND,created_at,UTC_TIMESTAMP(6))/1000 age_ms
            FROM outbox_event""")
        events = cur.fetchall()
        cur.execute("""SELECT p.status,
            TIMESTAMPDIFF(MICROSECOND,p.accepted_at,e.created_at)/1000 accepted_outbox_ms,
            TIMESTAMPDIFF(MICROSECOND,p.accepted_at,o.created_at)/1000 accepted_order_ms,
            TIMESTAMPDIFF(MICROSECOND,e.sent_at,o.created_at)/1000 confirmed_order_signed_ms,
            IF(p.status IN('SUCCEEDED','FAILED','EXPIRED'),TIMESTAMPDIFF(MICROSECOND,p.accepted_at,p.updated_at)/1000,NULL) terminal_ms
            FROM purchase_request p LEFT JOIN outbox_event e
            ON e.event_type='PURCHASE_REQUEST_ACCEPTED' AND e.event_id=CONCAT('purchase-',p.id)
            LEFT JOIN ticket_order o ON o.id=p.order_id""")
        purchases = cur.fetchall()
        db.commit()
    channels = {}
    for kind in sorted({'PURCHASE_REQUEST_ACCEPTED', 'WAITLIST_DISPATCH_REQUESTED'} | {e['event_type'] for e in events}):
        group = [e for e in events if e['event_type'] == kind]
        channels[kind] = {
            'states': {status: sum(e['status'] == status for e in group)
                       for status in ['PENDING', 'PROCESSING', 'SENT', 'FAILED']},
            'retryEvents': sum(e['retry_count'] > 1 for e in group),
            'createdToConfirmedRecordMs': distribution(e['confirmed_ms'] for e in group if e['status'] == 'SENT'),
            'pendingAgeMs': distribution(e['age_ms'] for e in group if e['status'] in ['PENDING', 'PROCESSING'])}
    return {'channels': channels, 'purchaseStates': {s: sum(p['status'] == s for p in purchases)
            for s in ['QUEUED', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'EXPIRED']},
        'acceptedToOutboxMs': distribution(p['accepted_outbox_ms'] for p in purchases),
        'acceptedToOrderWriteMs': distribution(p['accepted_order_ms'] for p in purchases),
        'acceptedToTerminalWriteMs': distribution(p['terminal_ms'] for p in purchases),
        'confirmedRecordToOrderWriteSignedMs': distribution(p['confirmed_order_signed_ms'] for p in purchases),
        'scope': '全轮数据库时间汇总，另看k6按完成时间截取的稳定窗口；写入时刻不等于事务提交时刻。',
        'overlap': '消费者可先于Publisher落账提交，确认落账到订单写入可为负数，不能当作独立顺序阶段或截断为零。发送到ACK及消费者事务分别看应用Timer，旧包没有该确认Timer。'}
