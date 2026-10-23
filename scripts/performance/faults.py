"""仅对 ticketing-perf 容器注入故障；使用真实 HTTP、数据库和 RabbitMQ。"""
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone, timedelta
import json
import time
import uuid
from urllib.parse import quote

import requests
import perf


def until(predicate, seconds=45):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        result = predicate()
        if result:
            return result
        time.sleep(0.25)
    raise RuntimeError('故障恢复条件未在期限内满足')


def restart(**overrides):
    app = json.loads((perf.PRIVATE / 'app.json').read_text(encoding='utf-8'))
    perf.app_stop()
    perf.app_start(app['jar'], **overrides)


def target(stock=100):
    adm = perf.admin()
    activity = perf.rows('SELECT MIN(id) id FROM activity')[0]['id']
    now = datetime.now(timezone.utc)
    session = adm.request('POST', f'/api/v1/admin/activities/{activity}/sessions', {
        'title': '故障验证场次' + uuid.uuid4().hex[:6], 'saleStartsAt': (now-timedelta(hours=1)).isoformat(),
        'startsAt': (now+timedelta(minutes=40)).isoformat(), 'endsAt': (now+timedelta(hours=3)).isoformat()})
    sku = adm.request('POST', f'/api/v1/admin/sessions/{session["id"]}/tickets', {
        'name': '故障验证票档', 'priceCents': '12800', 'totalStock': stock,
        'saleStartsAt': (now-timedelta(minutes=30)).isoformat(), 'saleEndsAt': (now+timedelta(minutes=35)).isoformat(),
        'maxQuantityPerOrder': 4})
    adm.request('POST', f'/api/v1/admin/tickets/{sku["id"]}/publish', {'version': sku['version']})
    return sku['id']


def mq(method, path, body=None):
    response = requests.request(method, 'http://127.0.0.1:25673/api' + path, json=body,
                                auth=('ticketing_perf', perf.config()['mqPassword']), timeout=10)
    response.raise_for_status()
    return response.json() if response.content else None


def main():
    output = perf.RESULTS / 'fault-recovery.json'
    if output.exists():
        raise RuntimeError('故障结果已存在，请先归档旧结果')
    report = {'startedAt': datetime.now(timezone.utc).isoformat(), 'checks': []}
    def checked(name, details):
        result = perf.audit()
        report['checks'].append({'name': name, 'passed': True, 'details': details, 'audit': result})
        perf.save(output, report)
        print('故障验证通过 ' + name, flush=True)
    perf.prepare('query', 10)
    restart(WAITLIST_COMPENSATION_POLL_MS='600000', ORDER_PAYMENT_WINDOW_MINUTES='2')
    fixture = json.loads((perf.PRIVATE / 'input.json').read_text(encoding='utf-8'))
    buyers = [perf.Client(u) for u in fixture['users']]
    assert requests.get(perf.BASE + perf.MONITOR + '/metrics', timeout=10).status_code == 401
    assert buyers[0].session.get(perf.BASE + perf.MONITOR + '/metrics', timeout=10).status_code == 403
    assert perf.admin().session.get(perf.BASE + perf.MONITOR + '/metrics', timeout=10).status_code == 200
    checked('压测指标端点权限隔离', {'anonymous':401,'user':403,'admin':200})
    holder = perf.Client(fixture['admins'][0])
    sku = target(1)
    occupied = holder.order(sku)
    entry = buyers[0].request('POST', '/api/v1/waitlist', {'skuId': sku, 'quantity': 1})
    before = perf.rows('SELECT COALESCE(MAX(id),0) id FROM outbox_event')[0]['id']
    perf.compose('stop', 'rabbitmq')
    holder.request('POST', f'/api/v1/orders/{occupied["id"]}/cancel')
    until(lambda: perf.rows("SELECT id FROM outbox_event WHERE id>%s AND retry_count>0 AND status IN ('PENDING','PROCESSING')", (before,)))
    pending = perf.rows("SELECT status,retry_count FROM outbox_event WHERE id>%s", (before,))
    assert buyers[0].request('GET', '/api/v1/waitlist/' + entry['id'])['status'] == 'WAITING'
    perf.compose('up', '-d', '--pull', 'never', '--wait', 'rabbitmq')
    allocated = until(lambda: (d if (d := buyers[0].request('GET', '/api/v1/waitlist/' + entry['id']))['status']=='ALLOCATED' else None))
    p1 = buyers[0].request('POST', f'/api/v1/orders/{allocated["reservedOrderId"]}/pay')
    p2 = buyers[0].request('POST', f'/api/v1/orders/{allocated["reservedOrderId"]}/pay')
    assert p1['paymentNo'] == p2['paymentNo']
    until(lambda: not perf.rows("SELECT id FROM outbox_event WHERE id>%s AND status<>'SENT'", (before,)))
    checked('RabbitMQ不可用后Outbox重试恢复', {'duringOutage': pending, 'paymentReplaySame': True})

    ticket = buyers[0].request('GET',f'/api/v1/tickets?orderId={allocated["reservedOrderId"]}')['items'][0]
    checkin_credential = buyers[0].request('GET',f'/api/v1/tickets/{ticket["id"]}/credential')['credential']
    stock_before = perf.rows('SELECT total_stock,locked_stock,sold_stock FROM ticket_sku WHERE id=%s',(sku,))
    checkers = [perf.Client(a) for a in fixture['admins']]
    def check_in(client):
        response = client.session.post(perf.BASE+'/api/v1/admin/checkins',json={'credential':checkin_credential},headers=client.token,timeout=30)
        assert response.status_code in (200,409)
        return response.status_code
    with ThreadPoolExecutor(max_workers=10) as pool:
        checkin_statuses = list(pool.map(check_in,checkers))
    used = perf.rows('SELECT status,used_at,checked_by FROM electronic_ticket WHERE id=%s',(ticket['id'],))[0]
    assert checkin_statuses.count(200)==1 and used['status']=='USED' and used['used_at'] and used['checked_by']
    assert perf.rows('SELECT total_stock,locked_stock,sold_stock FROM ticket_sku WHERE id=%s',(sku,))==stock_before
    checked('多管理员核销竞争并核对真实USED行',{'concurrentAdmins':10,'successes':1,'conflicts':9,'stockUnchanged':True})

    event = perf.rows("SELECT event_id,payload FROM outbox_event WHERE aggregate_id=%s ORDER BY id DESC LIMIT 1", (sku,))[0]
    count = perf.rows('SELECT COUNT(*) n FROM ticket_order')[0]['n']
    for _ in range(10):
        assert mq('POST', '/exchanges/%2F/ticketing.waitlist/publish', {
            'properties': {'message_id': event['event_id'], 'delivery_mode': 2, 'content_type': 'application/json'},
            'routing_key': 'inventory.available', 'payload': event['payload'], 'payload_encoding': 'string'})['routed']
    until(lambda: all(not q.get('messages_ready') and not q.get('messages_unacknowledged') for q in perf.mq_snapshot()))
    assert perf.rows('SELECT COUNT(*) n FROM ticket_order')[0]['n'] == count
    checked('Outbox重复消息不重复分配', {'duplicateDeliveries': 10, 'newOrders': 0})

    sku2 = target(1); order2 = holder.order(sku2)
    e2 = buyers[1].request('POST', '/api/v1/waitlist', {'skuId': sku2, 'quantity': 1})
    connections = {c['channel_details']['connection_name'] for c in mq('GET', '/consumers/%2F')}
    assert connections
    for connection in connections:
        mq('DELETE', '/connections/' + quote(connection, safe=''))
    holder.request('POST', f'/api/v1/orders/{order2["id"]}/cancel')
    a2 = until(lambda: (d if (d := buyers[1].request('GET', '/api/v1/waitlist/' + e2['id']))['status']=='ALLOCATED' else None))
    until(lambda: len(mq('GET', '/consumers/%2F')) >= 2)
    buyers[1].request('POST', f'/api/v1/orders/{a2["reservedOrderId"]}/cancel')
    checked('消费者连接中断后重新消费', {'closedConnections': len(connections), 'consumersRecovered': True})

    # 真正停止消费进程，向持久队列放入已提交Outbox事实；恢复时禁用补偿，单独验证MQ路径。
    restart(WAITLIST_JOBS_ENABLED='false', ORDER_PAYMENT_WINDOW_MINUTES='2')
    holder = perf.Client(fixture['admins'][0]); buyer = perf.Client(fixture['users'][4])
    recovery_sku = target(1); recovery_order = holder.order(recovery_sku)
    recovery_entry = buyer.request('POST','/api/v1/waitlist',{'skuId':recovery_sku,'quantity':1})
    holder.request('POST',f'/api/v1/orders/{recovery_order["id"]}/cancel')
    recovery_event = perf.rows('SELECT event_id,payload FROM outbox_event WHERE aggregate_id=%s ORDER BY id DESC LIMIT 1',(recovery_sku,))[0]
    app = json.loads((perf.PRIVATE/'app.json').read_text(encoding='utf-8')); perf.app_stop()
    assert mq('POST','/exchanges/%2F/ticketing.waitlist/publish',{
        'properties':{'message_id':recovery_event['event_id'],'delivery_mode':2,'content_type':'application/json'},
        'routing_key':'inventory.available','payload':recovery_event['payload'],'payload_encoding':'string'})['routed']
    until(lambda: mq('GET','/queues/%2F/ticketing.waitlist.dispatch').get('messages_ready',0)>=1)
    perf.app_start(app['jar'],WAITLIST_JOBS_ENABLED='false',ORDER_PAYMENT_WINDOW_MINUTES='2')
    buyer = perf.Client(fixture['users'][4])
    recovered = until(lambda: (d if (d:=buyer.request('GET','/api/v1/waitlist/'+recovery_entry['id']))['status']=='ALLOCATED' else None))
    buyer.request('POST',f'/api/v1/orders/{recovered["reservedOrderId"]}/pay')
    checked('消费进程退出后重启处理持久积压',{'databaseCompensationEnabled':False,'durableQueuedMessagesAtLeast':1,'recoveredAndPaid':True})

    # 暂停两个后台通道，制造已提交但未调度事实；重启只启用数据库补偿。
    restart(WAITLIST_JOBS_ENABLED='false', WAITLIST_MESSAGING_ENABLED='false', ORDER_PAYMENT_WINDOW_MINUTES='2')
    holder = perf.Client(fixture['admins'][0]); buyers = [perf.Client(u) for u in fixture['users']]
    sku3 = target(1); o3 = holder.order(sku3)
    e3 = buyers[2].request('POST', '/api/v1/waitlist', {'skuId': sku3, 'quantity': 1})
    holder.request('POST', f'/api/v1/orders/{o3["id"]}/cancel')
    assert buyers[2].request('GET', '/api/v1/waitlist/' + e3['id'])['status']=='WAITING'
    restart(WAITLIST_MESSAGING_ENABLED='false', ORDER_PAYMENT_WINDOW_MINUTES='2')
    buyers = [perf.Client(u) for u in fixture['users']]
    a3 = until(lambda: (d if (d := buyers[2].request('GET', '/api/v1/waitlist/' + e3['id']))['status']=='ALLOCATED' else None))
    buyers[2].request('POST', f'/api/v1/orders/{a3["reservedOrderId"]}/pay')
    checked('应用重启后数据库补偿恢复候补', {'messagingEnabled': False, 'allocatedAndPaid': True})

    restart(ORDER_PAYMENT_WINDOW_MINUTES='2')
    buyers = [perf.Client(u) for u in fixture['users']]
    sku4 = target(10); committed = buyers[3].order(sku4)
    key = 'fault-db-' + uuid.uuid4().hex
    perf.compose('stop', 'mysql')
    observed = None
    try:
        response = buyers[3].session.post(perf.BASE + '/api/v1/orders', json={'skuId':sku4,'quantity':1},
                                         headers={**buyers[3].token, 'Idempotency-Key':key}, timeout=3)
        observed = str(response.status_code)
    except requests.RequestException as error:
        observed = type(error).__name__
    perf.compose('up', '-d', '--pull', 'never', '--wait', 'mysql')
    until(lambda: requests.get(perf.BASE + '/api/v1/auth/csrf', timeout=3).status_code == 200)
    retry = buyers[3].request('POST', '/api/v1/orders', {'skuId':sku4,'quantity':1}, {'Idempotency-Key':key}, statuses=(200,202))
    replay = buyers[3].request('POST', '/api/v1/orders', {'skuId':sku4,'quantity':1}, {'Idempotency-Key':key}, statuses=(200,202))
    assert retry['requestId'] == replay['requestId']
    retry = buyers[3].resolve_purchase(retry)
    assert buyers[3].request('GET', '/api/v1/orders/' + committed['id'])['status'] == 'PENDING'
    for order_id in [committed['id'], retry['id']]: buyers[3].request('POST', f'/api/v1/orders/{order_id}/cancel')
    replay_checker = perf.Client(fixture['admins'][1])
    replay_response = replay_checker.session.post(perf.BASE+'/api/v1/admin/checkins',json={'credential':checkin_credential},headers=replay_checker.token,timeout=30)
    assert replay_response.status_code==409
    assert perf.rows('SELECT status,used_at,checked_by FROM electronic_ticket WHERE id=%s',(ticket['id'],))[0]==used
    checkin_credential = None
    checked('数据库连接中断后交易事实与幂等恢复', {'faultResponse': observed, 'committedOrderPreserved': True, 'retrySameOrder':True,'usedTicketReplay409':True,'originalCheckinPreserved':True})

    restart()
    buyers = [perf.Client(u) for u in fixture['users']]
    sku5 = target(10)
    historical = [buyers[i].order(sku5) for i in range(2)]
    disabled = perf.rows('SELECT id FROM user_account WHERE username=%s', (fixture['users'][1]['username'],))[0]['id']
    perf.execute("UPDATE user_account SET status='DISABLED' WHERE id=%s", (disabled,))
    app = json.loads((perf.PRIVATE / 'app.json').read_text(encoding='utf-8'))
    perf.app_stop()
    print('等待真实数据库截止时间，应用保持停止以验证重启补偿', flush=True)
    until(lambda: perf.rows("SELECT COUNT(*) n FROM ticket_order WHERE id IN (%s,%s) AND expires_at<=UTC_TIMESTAMP(6)",
                           (historical[0]['id'],historical[1]['id']))[0]['n']==2, 80)
    assert perf.rows("SELECT COUNT(*) n FROM ticket_order WHERE id IN (%s,%s) AND status='PENDING'", (historical[0]['id'],historical[1]['id']))[0]['n']==2
    perf.app_start(app['jar'])
    until(lambda: perf.rows("SELECT COUNT(*) n FROM ticket_order WHERE id IN (%s,%s) AND status='EXPIRED'", (historical[0]['id'],historical[1]['id']))[0]['n']==2)
    checked('关单任务中断后重启补偿并释放禁用账号库存', {'expiredOrders':2,'disabledAccounts':1,'databaseDeadlineUsed':True})
    perf.execute("UPDATE user_account SET status='ACTIVE' WHERE id=%s", (disabled,))

    buyers = [perf.Client(u) for u in fixture['users']]
    sku6 = target(20)
    competing = [buyers[i].order(sku6) for i in range(10)]
    first = min(datetime.fromisoformat(o['expiresAt'].replace('Z','+00:00')) for o in competing)
    while datetime.now(timezone.utc) < first - timedelta(milliseconds=100): time.sleep(0.1)
    def action(pair):
        i, op = pair
        c = perf.Client(fixture['users'][i])
        response = c.session.post(perf.BASE + f'/api/v1/orders/{competing[i]["id"]}/{op}', headers=c.token, timeout=30)
        assert response.status_code in (200,409)
        return response.status_code
    with ThreadPoolExecutor(max_workers=20) as pool:
        statuses = list(pool.map(action, [(i,op) for i in range(10) for op in ['pay','cancel']]))
    until(lambda: not perf.rows("SELECT id FROM ticket_order WHERE session_id=(SELECT session_id FROM ticket_sku WHERE id=%s) AND status='PENDING'", (sku6,)))
    states = perf.rows("SELECT o.status,COUNT(*) n FROM ticket_order o JOIN ticket_order_item i ON i.order_id=o.id WHERE i.sku_id=%s GROUP BY o.status", (sku6,))
    checked('真实HTTP支付取消与到期扫描竞争', {'orders':10,'responses200':statuses.count(200),'responses409':statuses.count(409),'finalStates':states})
    until(lambda: not perf.rows("SELECT id FROM outbox_event WHERE status IN ('PENDING','PROCESSING') LIMIT 1"))
    report['completedAt'] = datetime.now(timezone.utc).isoformat()
    report['finalAudit'] = perf.audit(); report['passed'] = True
    perf.save(output, report)


if __name__ == '__main__':
    main()
