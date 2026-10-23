"""仅在固定ticketing-perf环境验证生产阈值及真实Redis停机；共享输出只有聚合事实。"""
import argparse
from concurrent.futures import ThreadPoolExecutor
import json
import re
import time

import requests
import perf


def verify(label):
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,63}', label):
        raise RuntimeError('报告标签只允许字母数字下划线和连字符')
    output = perf.RESULTS / label / 'rate-limits.json'
    if output.exists():
        raise RuntimeError('结果已存在，须使用新标签')
    app = json.loads((perf.PRIVATE / 'app.json').read_text(encoding='utf-8'))
    if app.get('rateLimitMode') != 'production':
        raise RuntimeError('须显式以production限流模式启动专用应用')
    fixture = json.loads((perf.PRIVATE / 'input.json').read_text(encoding='utf-8'))
    if fixture.get('environment') != 'ticketing-perf-only' or fixture.get('baseUrl') != perf.BASE:
        raise RuntimeError('夹具环境不匹配')
    perf.connect().close()
    client = perf.Client(fixture['users'][0])
    before = perf.audit()
    def submit(path, body=None, session=None, token=None):
        session = session or client.session
        response = session.post(perf.BASE + '/api/v1' + path, json=body or {},
                                headers=token or client.token, timeout=5)
        return response
    def rejected(response, status):
        expected = 'TOO_MANY_REQUESTS' if status == 429 else 'RATE_LIMIT_UNAVAILABLE'
        if (response.status_code != status or response.json().get('code') != expected
                or not 1 <= int(response.headers.get('Retry-After', 0)) <= 600
                or 'no-store' not in response.headers.get('Cache-Control', '')):
            raise RuntimeError('安全拒绝响应协议不匹配')
    def register(_):
        # 无效JSON保留真实Controller校验且不创建账号；并发穿过CSRF及Redis Lua。
        browser = perf.Client()
        response = submit('/auth/register', session=browser.session, token=browser.token)
        if response.status_code == 429:
            rejected(response, 429)
        return response.status_code
    with ThreadPoolExecutor(max_workers=32) as pool:
        statuses = list(pool.map(register, range(160)))
    if statuses.count(400) != 10 or statuses.count(429) != 150:
        raise RuntimeError('真实HTTP注册并发放行数量错误')
    results = {'environment': 'ticketing-perf-only', 'rateLimitMode': 'production',
               'concurrentRegistration': {'requests': 160, 'controllerValidation400': 10, 'limited429': 150}}
    for path, limit in [('/orders', 30), ('/waitlist', 10)]:
        for _ in range(limit):
            if submit(path).status_code != 400:
                raise RuntimeError('业务限流预算或Controller校验不匹配')
        rejected(submit(path), 429)
    results['businessBudgets'] = {'orders': 30, 'waitlist': 10, 'rejectedBeforeWrites': True}
    if perf.audit() != before:
        raise RuntimeError('限流/无效提交改变了数据库业务事实')
    # 故障仅针对固定专用Compose的redis服务；finally保证恢复，不停止开发Redis。
    try:
        perf.compose('stop', 'redis')
        times = []
        for path in ['/auth/register', '/orders', '/waitlist']:
            started = time.monotonic()
            rejected(submit(path), 503)
            times.append(round((time.monotonic() - started) * 1000, 2))
        started = time.monotonic()
        response = client.session.post(perf.BASE + '/api/v1/auth/login',
                                       data=fixture['users'][0], headers=client.token, timeout=5)
        rejected(response, 503)
        times.append(round((time.monotonic() - started) * 1000, 2))
        public_status = client.session.get(perf.BASE + '/api/v1/activities', timeout=5).status_code
        orders_status = client.session.get(perf.BASE + '/api/v1/orders', timeout=5).status_code
        if public_status != 200 or orders_status != 200 or perf.audit() != before:
            raise RuntimeError('Redis故障影响了公开读取、既有会话查询或业务事实')
        results['realRedisOutage'] = {'protected503': 4, 'latencyMs': times,
                                     'publicRead': public_status, 'sessionOrderRead': orders_status,
                                     'databaseUnchanged': True}
    finally:
        perf.compose('up', '-d', '--pull', 'never', '--wait', '--wait-timeout', '60', 'redis')
    # 专用Redis使用tmpfs，重启可丢失短期计数；重连退避后须恢复真实写入口。
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        response = submit('/auth/register')
        if response.status_code == 400:
            break
        time.sleep(0.5)
    else:
        raise RuntimeError('Redis重启后请求频率校验未恢复')
    results['recovery'] = {'registrationControllerValidation': 400, 'audit': perf.audit()}
    perf.save(output, results)
    print('生产阈值、160并发、真实Redis停机及恢复验证通过')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--label', required=True)
    verify(parser.parse_args().label)
