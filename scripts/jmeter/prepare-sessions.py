#!/usr/bin/env python3
"""仅在隔离环境中预登录独立测试账号；会话和 CSRF 只写入本地私有 CSV。"""
import argparse
import concurrent.futures
import csv
import hashlib
import http.cookiejar
import importlib.util
import json
import subprocess
import time
import urllib.parse
import urllib.request
from pathlib import Path

import pymysql

ROOT = Path(__file__).resolve().parents[2]
RUNTIME = (ROOT / '.local' / 'jmeter').resolve()
spec = importlib.util.spec_from_file_location('prepare_data', Path(__file__).with_name('prepare-data.py'))
data = importlib.util.module_from_spec(spec)
spec.loader.exec_module(data)


def check_isolation():
    env_path = RUNTIME / 'compose.env'
    env = data.read_env(env_path)
    if (env.get('MYSQL_HOST'), env.get('MYSQL_PORT'), env.get('MYSQL_DATABASE'), env.get('SERVER_PORT')) != ('127.0.0.1', '13307', 'ticketingjmeter', '18080'):
        raise RuntimeError('隔离配置不匹配')
    data.verify_docker_volume(env_path)
    for service, port, inside in (('mysql','13307','3306/tcp'),('redis','16379','6379/tcp'),('rabbitmq','15673','5672/tcp')):
        raw = subprocess.run(['docker','inspect',f'ticketing-jmeter-{service}-1'], check=True, capture_output=True, text=True).stdout
        container = json.loads(raw)[0]
        labels = container['Config'].get('Labels') or {}
        volumes = {m.get('Name') for m in container.get('Mounts', []) if m.get('Type') == 'volume'}
        ports = (container.get('NetworkSettings', {}).get('Ports') or {}).get(inside) or []
        if (labels.get('com.docker.compose.project') != 'ticketing-jmeter' or labels.get('com.docker.compose.service') != service
                or f'ticketing-jmeter_{service}_data' not in volumes
                or not any(p.get('HostIp') == '127.0.0.1' and p.get('HostPort') == port for p in ports)):
            raise RuntimeError(f'{service} 容器、数据卷或端口不属于隔离环境')
    with pymysql.connect(host='127.0.0.1', port=13307, user='root', password=env['MYSQL_ROOT_PASSWORD'], database='ticketingjmeter') as conn:
        with conn.cursor() as cur:
            cur.execute('SELECT token_sha256 FROM jmeter_environment_guard WHERE id=1')
            row = cur.fetchone()
            if not row or row[0] != hashlib.sha256(env['JMETER_GUARD_TOKEN'].encode()).hexdigest():
                raise RuntimeError('隔离数据库 guard 不匹配')


def login(row):
    jar = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
    base = 'http://127.0.0.1:18080'
    def call(path, body=None, headers=None):
        request = urllib.request.Request(base + path, data=body, headers=headers or {})
        with opener.open(request, timeout=15) as response:
            if response.status != 200:
                raise RuntimeError('认证接口未成功')
            return json.load(response)['data']
    csrf = call('/api/v1/auth/csrf')
    form = urllib.parse.urlencode({'username': row['username'], 'password': row['password']}).encode()
    call('/api/v1/auth/login', form, {csrf['headerName']: csrf['token'], 'Content-Type': 'application/x-www-form-urlencoded'})
    csrf = call('/api/v1/auth/csrf')
    me = call('/api/v1/auth/me')
    if me.get('username') != row['username'] or not str(me.get('id', '')).isdigit():
        raise RuntimeError('会话身份校验失败')
    cookie = '; '.join(f'{c.name}={c.value}' for c in jar)
    if not cookie or not csrf.get('token') or not csrf.get('headerName'):
        raise RuntimeError('会话或 CSRF 未就绪')
    return {'cookie': cookie, 'csrfHeader': csrf['headerName'], 'csrfToken': csrf['token'], 'userId': str(me['id'])}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--users', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--workers', type=int, default=12)
    args = parser.parse_args()
    if not 1 <= args.workers <= 20 or not args.users.resolve().is_relative_to(RUNTIME) or not args.output.resolve().is_relative_to(RUNTIME):
        parser.error('只允许本地隔离文件和 1–20 个预登录线程')
    if args.output.exists():
        parser.error('会话文件已存在，拒绝覆盖')
    check_isolation()
    with args.users.open(encoding='utf-8-sig', newline='') as f:
        users = list(csv.DictReader(f))
    if not users or len({u['username'] for u in users}) != len(users):
        raise RuntimeError('账号为空或重复')
    started = time.monotonic()
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as pool:
        sessions = list(pool.map(login, users))
    if len({s['cookie'] for s in sessions}) != len(sessions) or len({s['userId'] for s in sessions}) != len(sessions):
        raise RuntimeError('会话不唯一')
    with args.output.open('x', encoding='utf-8', newline='') as f:
        writer = csv.DictWriter(f, fieldnames=['cookie', 'csrfHeader', 'csrfToken', 'userId'])
        writer.writeheader()
        writer.writerows(sessions)
    print(json.dumps({'validatedSessions': len(sessions), 'elapsedSeconds': round(time.monotonic()-started, 2), 'workers': args.workers}, ensure_ascii=False))


if __name__ == '__main__':
    main()
