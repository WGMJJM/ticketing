"""只读HTTP部署检查；可选管理员身份仅在内存中使用，不导出Cookie或认证响应。"""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
from urllib.parse import urlsplit
import requests


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', required=True)
    parser.add_argument('--output', required=True)
    parser.add_argument('--performance', action='store_true')
    parser.add_argument('--require-https', action='store_true')
    args = parser.parse_args()
    url = urlsplit(args.base_url)
    if url.scheme not in ('http', 'https') or not url.hostname or url.username or url.password or url.query or url.fragment or url.path not in ('', '/'):
        raise RuntimeError('只接受不带凭据和路径的HTTP来源地址')
    if args.require_https and url.scheme != 'https':
        raise RuntimeError('生产检查要求HTTPS')
    if url.scheme == 'http' and url.hostname not in ('127.0.0.1', 'localhost', '::1'):
        raise RuntimeError('明文HTTP检查仅用于本机；远程必须HTTPS')
    output = Path(args.output)
    if output.exists():
        raise RuntimeError('检查证据已存在，禁止覆盖')
    base = args.base_url.rstrip('/')
    checks = []
    def check(name, condition):
        checks.append({'name': name, 'passed': bool(condition)})
    session = requests.Session()
    response = session.get(base + '/api/v1/auth/csrf', timeout=10, allow_redirects=False)
    check('安全令牌接口及禁止缓存', response.status_code == 200 and 'no-store' in response.headers.get('Cache-Control', ''))
    token = response.json()['data']
    cookies = list(session.cookies)
    check('Session Cookie为HttpOnly及SameSite', any('HttpOnly' in c._rest and c._rest.get('SameSite', '').lower() == 'lax' for c in cookies))
    if args.require_https:
        check('生产Session Cookie为Secure', all(c.secure for c in cookies))
    check('匿名不能查询订单', session.get(base + '/api/v1/orders', timeout=10, allow_redirects=False).status_code == 401)
    check('公开活动查询可用', session.get(base + '/api/v1/activities?size=12', timeout=10, allow_redirects=False).status_code == 200)
    check('敏感Actuator端点未公开', all(session.get(base + path, timeout=10, allow_redirects=False).status_code in (401, 403, 404)
        for path in ['/actuator/env', '/actuator/configprops', '/actuator/metrics']))
    if args.performance:
        check('诊断端点拒绝匿名', session.get(base + '/api/v1/admin/monitoring/metrics', timeout=10, allow_redirects=False).status_code == 401)
        account = {'username': os.environ['TICKETING_SMOKE_USERNAME'], 'password': os.environ['TICKETING_SMOKE_PASSWORD']}
        login = session.post(base + '/api/v1/auth/login', data=account, headers={token['headerName']: token['token']}, timeout=15, allow_redirects=False)
        check('诊断使用真实管理员会话', login.status_code == 200)
        info = session.get(base + '/api/v1/admin/monitoring/info', timeout=10).json()
        check('专用环境身份标记', info.get('performance', {}).get('environment') == 'ticketing-perf-only')
        health = session.get(base + '/api/v1/admin/monitoring/health', timeout=10)
    else:
        health = session.get(base + '/actuator/health', timeout=10)
    check('MySQLRedisRabbit健康汇总', health.status_code == 200 and health.json().get('status') == 'UP')
    report = {'at': datetime.now(timezone.utc).isoformat(), 'baseUrl': base, 'readOnlyBusinessChecks': True,
              'authenticationStored': False, 'productionHttpsChecked': args.require_https,
              'passed': all(c['passed'] for c in checks), 'checks': checks}
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    session.close()
    print('部署检查', '通过' if report['passed'] else '未通过', str(output))
    if not report['passed']:
        raise RuntimeError('部署检查未通过，请查看不含凭据的聚合报告')


if __name__ == '__main__':
    main()
