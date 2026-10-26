"""功能验收共用的隔离连接、应用启动和只读业务审计，不含负载生成或性能统计。"""
import base64
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import time
import uuid
import pymysql
import requests

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
PRIVATE = ROOT / '.local' / 'verification'
BASE = 'http://127.0.0.1:18080'
MONITOR = '/api/v1/admin/monitoring'
TABLES = ['purchase_request', 'order_aftercare', 'refund_record', 'electronic_ticket', 'waitlist_entry',
          'payment_record', 'ticket_order_item', 'ticket_order', 'outbox_event', 'ticket_sku',
          'activity_session', 'activity', 'user_account']
OVERRIDES = {'ORDER_PAYMENT_WINDOW_MINUTES', 'ORDER_EXPIRY_SCAN_DELAY_MS', 'ORDER_EXPIRY_ENABLED',
             'PURCHASE_MESSAGING_ENABLED', 'PURCHASE_QUEUE_SECONDS', 'PURCHASE_RECOVERY_SECONDS'}


def save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2, default=str), encoding='utf-8')

def config():
    if not (PRIVATE / 'environment.json').is_file():
        raise RuntimeError('先按docs/40-functional-verification.md配置独立功能验收环境；不要加载业务.env')
    value = json.loads((PRIVATE / 'environment.json').read_text(encoding='utf-8'))
    if value.get('environment') != 'ticketing-verification-only':
        raise RuntimeError('专用环境标记不匹配，禁止操作')
    return value

def connect():
    cfg = config()
    db = pymysql.connect(host='127.0.0.1', port=13307, user='root', password=cfg['rootPassword'],
                         database='ticketing_verify', autocommit=True, charset='utf8mb4',
                         cursorclass=pymysql.cursors.DictCursor, connect_timeout=5,
                         read_timeout=15, write_timeout=15)
    with db.cursor() as cur:
        cur.execute("SET time_zone='+00:00'")
        cur.execute('SELECT token FROM performance_control.environment_guard')
        if cur.fetchone()['token'] != cfg['guardToken']:
            db.close()
            raise RuntimeError('数据库隔离标记错误，禁止写入或清理')
    return db

class Client:
    def __init__(self, account=None, register=False, session=None):
        self.session = session if session is not None else requests.Session()
        self.csrf()
        if register:
            self.request('POST', '/api/v1/auth/register', account)
        if account:
            response = self.session.post(BASE + '/api/v1/auth/login', data=account,
                                         headers=self.token, timeout=20)
            if response.status_code != 200:
                raise RuntimeError('测试账号登录失败')
            self.csrf()

    def csrf(self):
        data = self.session.get(BASE + '/api/v1/auth/csrf', timeout=10).json()['data']
        self.token = {data['headerName']: data['token']}

    def request(self, method, path, data=None, headers=None, statuses=(200,)):
        response = self.session.request(method, BASE + path, json=data,
                                        headers={**self.token, **(headers or {})}, timeout=30)
        if response.status_code not in statuses:
            # 不包含请求/响应正文，尤其不得将电子票凭证和密码写入异常日志。
            raise RuntimeError(f'接口调用失败 {method} 状态码 {response.status_code}')
        return response.json() if path.startswith(MONITOR) else response.json().get('data')

    def order(self, sku, quantity=1):
        accepted = self.request('POST', '/api/v1/orders', {'skuId': str(sku), 'quantity': quantity},
                                {'Idempotency-Key': str(uuid.uuid4())}, statuses=(200, 202))
        return self.resolve_purchase(accepted)

    def resolve_purchase(self, accepted):
        deadline = time.monotonic() + 150
        while accepted['status'] in ('QUEUED', 'PROCESSING') and time.monotonic() < deadline:
            time.sleep(0.25)
            accepted = self.request('GET', '/api/v1/purchase-requests/' + accepted['requestId'])
        if accepted['status'] != 'SUCCEEDED':
            raise RuntimeError('专用购票未成功结束 ' + str(accepted.get('failureCode') or accepted['status']))
        return self.request('GET', '/api/v1/orders/' + accepted['orderId'])

def admin():
    return Client({'username': 'verify_admin', 'password': config()['adminPassword']})

def rate_limit_environment(mode):
    if mode not in ('regression', 'production'):
        raise RuntimeError('未知专用限流模式')
    values = {'VERIFY_RATE_LIMIT_ENABLED': 'true',
              'VERIFY_RATE_LIMIT_KEY_SECRET': base64.b64encode(secrets.token_bytes(32)).decode()}
    if mode == 'regression':
        values.update({f'VERIFY_RATE_LIMIT_{category}': '10000' for category in
                       ('LOGIN_IP', 'LOGIN_ACCOUNT_IP', 'REGISTER_IP', 'ORDER_USER', 'WAITLIST_USER', 'CSRF_SESSION_IP')})
    return values

def app_start(jar, rate_limit_mode='regression', **overrides):
    if not set(overrides).issubset(OVERRIDES):
        raise RuntimeError('只允许覆盖已列出的无敏感功能验收参数')
    if any(not re.fullmatch(r'(?:true|false|[0-9]{1,8})', str(value)) for value in overrides.values()):
        raise RuntimeError('无敏感功能验收参数只接受布尔值或有限整数')
    cfg = config()
    pidfile = PRIVATE / 'app.pid'
    if pidfile.exists():
        raise RuntimeError('先运行 stop，避免两个功能验收进程共用端口')
    # 不继承可能重定向数据源/端口或注入JVM参数的开发环境；只在子进程中隔离。
    prefixes = ('SPRING_','MANAGEMENT_','TICKETING_','MYSQL_','RABBITMQ_','REDIS_','SERVER_',
                'ORDER_','WAITLIST_','PURCHASE_','OUTBOX_','CATALOG_','ADMIN_','SESSION_','TICKET_','CHECKIN_','LOGGING_','RATE_LIMIT_','VERIFY_','CLIENT_ADDRESS_')
    env = {key:value for key,value in os.environ.items()
           if not key.upper().replace('.','_').replace('-','_').startswith(prefixes)
           and key.upper() not in ('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS')}
    env.update(VERIFY_DB_PASSWORD=cfg['dbPassword'], VERIFY_MQ_PASSWORD=cfg['mqPassword'],
               VERIFY_ADMIN_PASSWORD=cfg['adminPassword'], VERIFY_TICKET_KEYS=cfg['ticketKeys'],
               MYSQL_PASSWORD=cfg['dbPassword'], RABBITMQ_PASSWORD=cfg['mqPassword'],
               SPRING_FLYWAY_USER='root', SPRING_FLYWAY_PASSWORD=cfg['rootPassword'])
    env.update(rate_limit_environment(rate_limit_mode))
    # 仅专用实例的迁移连接使用管理账号；运行连接池沿用迁移测试约定的受限账号。
    env.update({key: str(value) for key, value in overrides.items()})
    args = ['java', '-jar', str(Path(jar).resolve()),
            '--spring.config.additional-location=file:' + (HERE / 'application-verification.yml').as_posix()]
    out = (PRIVATE / 'app.out.log').open('ab')
    err = (PRIVATE / 'app.err.log').open('ab')
    process = subprocess.Popen(args, cwd=ROOT, env=env, stdout=out, stderr=err,
                               creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
    out.close(); err.close()
    pidfile.write_text(str(process.pid), encoding='ascii')
    save(PRIVATE / 'app.json', {'jar': str(Path(jar).resolve()), 'sha256': hashlib.sha256(Path(jar).read_bytes()).hexdigest(),
                              'overrides': overrides, 'rateLimitMode': rate_limit_mode,
                              'startedAt': datetime.now(timezone.utc).isoformat()})
    for _ in range(120):
        if process.poll() is not None:
            raise RuntimeError('功能验收应用启动失败，请查看 .local/verification/app.err.log')
        try:
            if requests.get(BASE + '/api/v1/auth/csrf', timeout=1).status_code == 200:
                info = admin().request('GET', MONITOR + '/info')
                if info.get('verification', {}).get('environment') == 'ticketing-verification-only':
                    print('专用功能验收应用已启动')
                    return
        except (requests.RequestException, RuntimeError):
            pass
        time.sleep(1)
    raise RuntimeError('功能验收应用启动超时')

def app_stop():
    path = PRIVATE / 'app.pid'
    if not path.exists():
        return
    pid = int(path.read_text())
    # 避免 PID 重用时终止无关进程；只允许本工具的专用配置进程。
    if os.name == 'nt':
        code = f"$p=Get-CimInstance Win32_Process -Filter 'ProcessId={pid}'; if ($p -and $p.CommandLine.Contains('scripts/verification/application-verification.yml')) {{ Stop-Process -Id {pid} -ErrorAction Stop }} elseif ($p) {{ throw 'PID归属不匹配' }}"
        subprocess.run(['powershell', '-NoProfile', '-Command', code], check=True, capture_output=True)
    else:
        cmd = Path(f'/proc/{pid}/cmdline').read_bytes()
        if b'application-verification.yml' not in cmd:
            raise RuntimeError('PID归属不匹配')
        os.kill(pid, 15)
    path.unlink()

def audit():
    sql = '\n'.join(line for line in (HERE / 'audit.sql').read_text(encoding='utf-8').splitlines()
                    if not line.lstrip().startswith('--'))
    checks = []
    # 一个一致性快照读取，避免在线审计跨提交导致伪差异。
    with connect() as db, db.cursor() as cur:
        cur.execute('SET TRANSACTION ISOLATION LEVEL REPEATABLE READ')
        cur.execute('START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY')
        cur.execute("SELECT COUNT(*) n FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='purchase_request'")
        async_schema = bool(cur.fetchone()['n'])
        if async_schema:
            sql += '\n' + '\n'.join(line for line in (HERE / 'audit-purchase.sql').read_text(encoding='utf-8').splitlines()
                                     if not line.lstrip().startswith('--'))
        cur.execute("SELECT COUNT(*) n FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='user_session_quota'")
        quota_schema = bool(cur.fetchone()['n'])
        if quota_schema:
            sql += '\n' + '\n'.join(line for line in (HERE / 'audit-quota.sql').read_text(encoding='utf-8').splitlines()
                                     if not line.lstrip().startswith('--'))
        cur.execute("SELECT COUNT(*) n FROM information_schema.views WHERE table_schema=DATABASE() AND table_name='ticket_stock_balance'")
        bucket_schema = bool(cur.fetchone()['n'])
        if bucket_schema:
            sql = sql.replace('FROM ticket_sku', 'FROM ticket_stock_balance')
            sql += '\n' + '\n'.join(line for line in (HERE / 'audit-buckets.sql').read_text(encoding='utf-8').splitlines() if not line.lstrip().startswith('--'))
        for statement in sql.split(';'):
            if statement.strip():
                cur.execute(statement)
                checks.extend(cur.fetchall())
        counts = {}
        for table in TABLES:
            if table == 'purchase_request' and not async_schema:
                continue
            cur.execute(f'SELECT COUNT(*) n FROM {table}')
            counts[table] = cur.fetchone()['n']
        if quota_schema:
            cur.execute('SELECT COUNT(*) n FROM user_session_quota')
            counts['user_session_quota'] = cur.fetchone()['n']
        db.commit()
    result = {'passed': all(r['violation_count'] == 0 for r in checks), 'checks': checks, 'counts': counts}
    if not result['passed']:
        raise RuntimeError('数据库一致性审计失败 ' + json.dumps(checks))
    return result
