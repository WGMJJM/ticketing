"""真实浏览器验收夹具；仅guard验证的固定独立功能验收，账号只存忽略目录。"""
import argparse
from datetime import datetime, timedelta, timezone
import json
import hashlib
import secrets
import re
import time
import uuid
import environment as verification

PRIVATE = verification.ROOT / '.local/verification/browser-fixture.json'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar')
    parser.add_argument('--label', default='browser-audit')
    parser.add_argument('--action', choices=['prepare', 'queue-timeout', 'order-timeout', 'resume', 'audit'], default='prepare')
    args = parser.parse_args()
    verification.connect().close()
    if args.action == 'audit':
        if not re.fullmatch(r'[A-Za-z0-9_-]{1,50}', args.label):
            raise RuntimeError('审计标签无效')
        output = verification.ROOT / '.local/verification/results' / (args.label + '.json')
        if output.exists():
            raise RuntimeError('审计证据已存在，禁止覆盖')
        result = verification.audit()
        verification.save(output, result)
        print('浏览器后SQL审计', result['passed'])
        return
    if args.action != 'prepare':
        app = json.loads((verification.PRIVATE / 'app.json').read_text(encoding='utf-8'))
        verification.app_stop()
        values = {'ORDER_PAYMENT_WINDOW_MINUTES': '15'}
        if args.action == 'queue-timeout':
            values.update(PURCHASE_MESSAGING_ENABLED='false', PURCHASE_QUEUE_SECONDS='5', PURCHASE_RECOVERY_SECONDS='1')
        if args.action == 'order-timeout':
            values['ORDER_PAYMENT_WINDOW_MINUTES'] = '1'
        verification.app_start(app['jar'], rate_limit_mode='regression', **values)
        return
    if PRIVATE.exists():
        raise RuntimeError('夹具已存在；保留旧证据后使用独立工作目录或显式人工重命名私有夹具')
    if not args.jar:
        raise RuntimeError('必须提供本轮JAR')
    verification.app_stop()
    verification.app_start(args.jar, rate_limit_mode='regression', ORDER_PAYMENT_WINDOW_MINUTES='15')
    operator = verification.admin()
    run = uuid.uuid4().hex[:8]
    users = [{'username': 's19_' + run + '_' + str(i), 'email': f's19_{run}_{i}@example.test',
              'password': secrets.token_urlsafe(20)} for i in range(5)]
    for account in users[1:]:
        client = verification.Client()
        client.request('POST', '/api/v1/auth/email-code', {'email': account['email']})
        mailbox = verification.PRIVATE / 'mail' / (hashlib.sha256(account['email'].encode()).hexdigest() + '.code')
        code = mailbox.read_text(encoding='ascii')
        client.request('POST', '/api/v1/auth/register', {**account, 'code': code})
    now = datetime.now(timezone.utc)
    activity = operator.request('POST', '/api/v1/admin/activities', {
        'title': '第十九阶段浏览器演示 ' + run, 'category': 'CONCERT', 'city': '上海',
        'venue': '隔离测试场馆', 'summary': '浏览器真实业务验收', 'description': '仅用于独立验收，不是真实演出'})
    operator.request('POST', '/api/v1/admin/activities/' + activity['id'] + '/publish', {'version': activity['version']})
    def target(name, stock):
        session = operator.request('POST', '/api/v1/admin/activities/' + activity['id'] + '/sessions', {
            'title': name, 'saleStartsAt': (now - timedelta(hours=1)).isoformat(),
            'startsAt': (now + timedelta(minutes=40)).isoformat(), 'endsAt': (now + timedelta(hours=3)).isoformat()})
        sku = operator.request('POST', '/api/v1/admin/sessions/' + session['id'] + '/tickets', {
            'name': name + '票档', 'priceCents': '12800', 'totalStock': stock,
            'saleStartsAt': (now - timedelta(minutes=30)).isoformat(),
            'saleEndsAt': (now + timedelta(minutes=35)).isoformat(), 'maxQuantityPerOrder': 4})
        sku = operator.request('POST', '/api/v1/admin/tickets/' + sku['id'] + '/publish', {'version': sku['version']})
        return {'activityId': activity['id'], 'sessionId': session['id'], 'skuId': sku['id'], 'title': name}
    main = target('正常购票场次', 20)
    waitlist = target('FIFO候补场次', 2)
    aftercare = target('取消善后场次', 10)
    # 新票档由既有库存同步任务初始化；在真实购票前等待三个隔离票档就绪。
    deadline = time.monotonic() + 20
    while True:
        with verification.connect() as db, db.cursor() as cur:
            cur.execute('SELECT COUNT(*) n FROM ticket_sku WHERE id IN (%s,%s,%s) AND redis_inventory_initialized=1',
                        (main['skuId'], waitlist['skuId'], aftercare['skuId']))
            if cur.fetchone()['n'] == 3:
                break
        if time.monotonic() >= deadline:
            raise RuntimeError('隔离票档库存初始化超时')
        time.sleep(0.2)
    holder = verification.Client(users[3])
    holders = [holder.order(waitlist['skuId'])['id'] for _ in range(2)]
    aftercare_order = holder.order(aftercare['skuId'])['id']
    holder.request('POST', '/api/v1/orders/' + aftercare_order + '/pay')
    verification.save(PRIVATE, {'environment': 'ticketing-verification-only', 'run': run, 'activity': activity,
        'main': main, 'waitlist': waitlist, 'aftercare': aftercare, 'aftercareOrder': aftercare_order,
        'holderOrders': holders, 'users': users, 'admin': {'username': 'verify_admin', 'password': verification.config()['adminPassword']}})
    print('真实API浏览器夹具已准备，账号仅存.local')


if __name__ == '__main__':
    main()
