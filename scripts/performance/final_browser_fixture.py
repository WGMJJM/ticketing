"""真实浏览器验收夹具；仅guard验证的固定ticketing-perf，账号只存忽略目录。"""
import argparse
from datetime import datetime, timedelta, timezone
import json
import secrets
import re
import uuid
import perf

PRIVATE = perf.ROOT / '.local/stage19/browser-fixture.json'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar')
    parser.add_argument('--label', default='stage19-browser-audit')
    parser.add_argument('--action', choices=['prepare', 'queue-timeout', 'order-timeout', 'resume', 'audit'], default='prepare')
    args = parser.parse_args()
    perf.connect().close()
    if args.action == 'audit':
        if not re.fullmatch(r'[A-Za-z0-9_-]{1,50}', args.label):
            raise RuntimeError('审计标签无效')
        output = perf.ROOT / 'docs/architecture/results' / (args.label + '.json')
        if output.exists():
            raise RuntimeError('审计证据已存在，禁止覆盖')
        result = perf.audit()
        perf.save(output, result)
        print('浏览器后SQL审计', result['passed'])
        return
    if args.action != 'prepare':
        app = json.loads((perf.PRIVATE / 'app.json').read_text(encoding='utf-8'))
        perf.app_stop()
        values = {'ORDER_PAYMENT_WINDOW_MINUTES': '15'}
        if args.action == 'queue-timeout':
            values.update(PURCHASE_MESSAGING_ENABLED='false', PURCHASE_QUEUE_SECONDS='5', PURCHASE_RECOVERY_SECONDS='1')
        if args.action == 'order-timeout':
            values['ORDER_PAYMENT_WINDOW_MINUTES'] = '1'
        perf.app_start(app['jar'], rate_limit_mode='regression', **values)
        return
    if PRIVATE.exists():
        raise RuntimeError('夹具已存在；保留旧证据后使用独立工作目录或显式人工重命名私有夹具')
    if not args.jar:
        raise RuntimeError('必须提供本轮JAR')
    perf.app_stop()
    perf.app_start(args.jar, rate_limit_mode='regression', ORDER_PAYMENT_WINDOW_MINUTES='15')
    operator = perf.admin()
    run = uuid.uuid4().hex[:8]
    users = [{'username': 's19_' + run + '_' + str(i), 'password': secrets.token_urlsafe(20)} for i in range(5)]
    for account in users[1:]:
        perf.Client(account, register=True)
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
    holder = perf.Client(users[3])
    holders = [holder.order(waitlist['skuId'])['id'] for _ in range(2)]
    aftercare_order = holder.order(aftercare['skuId'])['id']
    holder.request('POST', '/api/v1/orders/' + aftercare_order + '/pay')
    perf.save(PRIVATE, {'environment': 'ticketing-perf-only', 'run': run, 'activity': activity,
        'main': main, 'waitlist': waitlist, 'aftercare': aftercare, 'aftercareOrder': aftercare_order,
        'holderOrders': holders, 'users': users, 'admin': {'username': 'perf_admin', 'password': perf.config()['adminPassword']}})
    print('真实API浏览器夹具已准备，账号仅存.local')


if __name__ == '__main__':
    main()
