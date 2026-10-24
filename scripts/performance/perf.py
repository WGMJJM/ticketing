"""票务交易平台专用本地压测。凭据及进程输出只写入 .local/performance。"""
import argparse
import base64
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone, timedelta
import hashlib
import json
import os
import platform
import re
from pathlib import Path
import secrets
import shutil
import subprocess
import sys
import threading
import time
import uuid

import pymysql
import requests

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
PRIVATE = ROOT / '.local' / 'performance'
RESULTS = ROOT / 'docs' / 'performance' / 'results'
BASE = 'http://127.0.0.1:18080'
MONITOR = '/api/v1/admin/monitoring'
TABLES = ['purchase_request', 'order_aftercare', 'refund_record', 'electronic_ticket', 'waitlist_entry', 'payment_record', 'ticket_order_item',
          'ticket_order', 'outbox_event', 'ticket_sku', 'activity_session', 'activity', 'user_account']
OVERRIDES = {'WAITLIST_COMPENSATION_POLL_MS', 'WAITLIST_JOBS_ENABLED', 'WAITLIST_MESSAGING_ENABLED',
             'ORDER_PAYMENT_WINDOW_MINUTES', 'ORDER_EXPIRY_SCAN_DELAY_MS', 'ORDER_EXPIRY_ENABLED',
             'SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE', 'SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE',
             'TICKETING_WAITLIST_OUTBOX_BATCH_SIZE'}
OVERRIDES |= {'PURCHASE_CAPACITY', 'PURCHASE_QUEUE_SECONDS', 'PURCHASE_LEASE_SECONDS', 'PURCHASE_RECOVERY_SECONDS',
              'PURCHASE_JOBS_ENABLED', 'PURCHASE_MESSAGING_ENABLED', 'OUTBOX_JOBS_ENABLED'}
OVERRIDES |= {'CATALOG_CACHE_ENABLED', 'PURCHASE_ADMISSION_LIMIT_ENABLED', 'PURCHASE_ADMISSION_GLOBAL_RATE',
              'PURCHASE_ADMISSION_GLOBAL_BURST', 'PURCHASE_ADMISSION_TICKET_RATE', 'PURCHASE_ADMISSION_TICKET_BURST'}
OVERRIDES.add('ORDER_QUOTA_MIGRATION_APPROVED')


def save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2, default=str), encoding='utf-8')


def config():
    value = json.loads((PRIVATE / 'environment.json').read_text(encoding='utf-8'))
    if value.get('environment') != 'ticketing-perf-only':
        raise RuntimeError('专用环境标记不匹配，禁止操作')
    return value


def compose(*args, check=True):
    # 项目名、Compose 文件固定，故障操作不会匹配正常开发容器。
    endpoint_override = os.environ.get('DOCKER_HOST', '')
    if endpoint_override and not endpoint_override.startswith(('npipe://','unix://')):
        raise RuntimeError('压测仅允许本机Docker套接字上下文')
    endpoint = subprocess.check_output(['docker','context','inspect','--format','{{.Endpoints.docker.Host}}'],text=True).strip()
    if not endpoint.startswith(('npipe://','unix://')):
        raise RuntimeError('压测仅允许本机Docker套接字上下文')
    cfg = config()
    env = os.environ.copy()
    env.update(PERF_DB_PASSWORD=cfg['dbPassword'],PERF_ROOT_PASSWORD=cfg['rootPassword'],PERF_MQ_PASSWORD=cfg['mqPassword'])
    return subprocess.run(['docker', 'compose', '--project-name', 'ticketing-perf',
                           '--env-file', str(PRIVATE / 'compose.env'), '-f', str(HERE / 'compose.yml'),
                           *args], check=check, env=env, capture_output=True, text=True, encoding='utf-8', errors='replace')


def connect(guard=True):
    cfg = config()
    db = pymysql.connect(host='127.0.0.1', port=13307, user='root', password=cfg['rootPassword'],
                         database='ticketing_perf', autocommit=True, charset='utf8mb4',
                         cursorclass=pymysql.cursors.DictCursor, connect_timeout=5,
                         read_timeout=15, write_timeout=15)
    with db.cursor() as cur:
        cur.execute("SET time_zone='+00:00'")
        if guard:
            cur.execute('SELECT token FROM performance_control.environment_guard')
            if cur.fetchone()['token'] != cfg['guardToken']:
                db.close()
                raise RuntimeError('数据库隔离标记错误，禁止写入或清理')
    return db


def rows(sql, args=None):
    with connect() as db, db.cursor() as cur:
        cur.execute(sql, args)
        return cur.fetchall()


def execute(sql, args=None):
    with connect() as db, db.cursor() as cur:
        cur.execute(sql, args)
        return cur.lastrowid


class Client:
    def __init__(self, account=None, register=False):
        self.session = requests.Session()
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
        if 'requestId' not in accepted:
            return accepted  # 仅供显式运行旧基线JAR的工具兼容，不是当前HTTP同步旁路。
        deadline = time.monotonic() + 150
        while accepted['status'] in ('QUEUED', 'PROCESSING') and time.monotonic() < deadline:
            time.sleep(0.25)
            accepted = self.request('GET', '/api/v1/purchase-requests/' + accepted['requestId'])
        if accepted['status'] != 'SUCCEEDED':
            raise RuntimeError('专用购票未成功结束 ' + str(accepted.get('failureCode') or accepted['status']))
        return self.request('GET', '/api/v1/orders/' + accepted['orderId'])


def admin():
    return Client({'username': 'perf_admin', 'password': config()['adminPassword']})


def rate_limit_environment(mode):
    if mode not in ('baseline', 'regression', 'production'):
        raise RuntimeError('未知专用限流模式')
    values = {'PERF_RATE_LIMIT_ENABLED': 'false' if mode == 'baseline' else 'true',
              'PERF_RATE_LIMIT_KEY_SECRET': base64.b64encode(secrets.token_bytes(32)).decode()}
    if mode == 'regression':
        values.update({f'PERF_RATE_LIMIT_{category}': '10000' for category in
                       ('LOGIN_IP', 'LOGIN_ACCOUNT_IP', 'REGISTER_IP', 'ORDER_USER', 'WAITLIST_USER', 'CSRF_SESSION_IP')})
    return values


def app_start(jar, rate_limit_mode='regression', **overrides):
    if not set(overrides).issubset(OVERRIDES):
        raise RuntimeError('只允许覆盖已列出的无敏感压测参数')
    if any(not re.fullmatch(r'(?:true|false|[0-9]{1,8})', str(value)) for value in overrides.values()):
        raise RuntimeError('无敏感压测参数只接受布尔值或有限整数')
    cfg = config()
    pidfile = PRIVATE / 'app.pid'
    if pidfile.exists():
        raise RuntimeError('先运行 stop，避免两个压测进程共用端口')
    # 不继承可能重定向数据源/端口或注入JVM参数的开发环境；只在子进程中隔离。
    prefixes = ('SPRING_','MANAGEMENT_','TICKETING_','MYSQL_','RABBITMQ_','REDIS_','SERVER_',
                'ORDER_','WAITLIST_','PURCHASE_','OUTBOX_','CATALOG_','ADMIN_','SESSION_','TICKET_','CHECKIN_','LOGGING_','RATE_LIMIT_','PERF_','CLIENT_ADDRESS_')
    env = {key:value for key,value in os.environ.items()
           if not key.upper().replace('.','_').replace('-','_').startswith(prefixes)
           and key.upper() not in ('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS')}
    env.update(PERF_DB_PASSWORD=cfg['dbPassword'], PERF_MQ_PASSWORD=cfg['mqPassword'],
               PERF_ADMIN_PASSWORD=cfg['adminPassword'], PERF_TICKET_KEYS=cfg['ticketKeys'],
               MYSQL_PASSWORD=cfg['dbPassword'], RABBITMQ_PASSWORD=cfg['mqPassword'],
               SPRING_FLYWAY_USER='root', SPRING_FLYWAY_PASSWORD=cfg['rootPassword'])
    env.update(rate_limit_environment(rate_limit_mode))
    # 仅专用实例的迁移连接使用已有管理账号创建审计触发器；运行连接池仍用ticketing_perf。
    env.update({key: str(value) for key, value in overrides.items()})
    args = ['java', '-Xms512m', '-Xmx1024m', '-XX:+UseG1GC', '-jar', str(Path(jar).resolve()),
            '--spring.config.additional-location=file:' + (HERE / 'application-performance.yml').as_posix()]
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
            raise RuntimeError('压测应用启动失败，请查看 .local/performance/app.err.log')
        try:
            if requests.get(BASE + '/api/v1/auth/csrf', timeout=1).status_code == 200:
                info = admin().request('GET', MONITOR + '/info')
                if info.get('performance', {}).get('environment') == 'ticketing-perf-only':
                    print('专用压测应用已启动')
                    return
        except (requests.RequestException, RuntimeError):
            pass
        time.sleep(1)
    raise RuntimeError('压测应用启动超时')


def app_stop():
    path = PRIVATE / 'app.pid'
    if not path.exists():
        return
    pid = int(path.read_text())
    # 避免 PID 重用时终止无关进程；只允许本工具的专用配置进程。
    if os.name == 'nt':
        code = f"$p=Get-CimInstance Win32_Process -Filter 'ProcessId={pid}'; if ($p -and $p.CommandLine.Contains('scripts/performance/application-performance.yml')) {{ Stop-Process -Id {pid} -ErrorAction Stop }} elseif ($p) {{ throw 'PID归属不匹配' }}"
        subprocess.run(['powershell', '-NoProfile', '-Command', code], check=True, capture_output=True)
    else:
        cmd = Path(f'/proc/{pid}/cmdline').read_bytes()
        if b'application-performance.yml' not in cmd:
            raise RuntimeError('PID归属不匹配')
        os.kill(pid, 15)
    path.unlink()


def initialize():
    PRIVATE.mkdir(parents=True, exist_ok=True)
    if not (PRIVATE / 'environment.json').exists():
        save(PRIVATE / 'environment.json', {
            'environment': 'ticketing-perf-only', 'guardToken': secrets.token_hex(24),
            'dbPassword': secrets.token_urlsafe(24), 'rootPassword': secrets.token_urlsafe(24),
            'mqPassword': secrets.token_urlsafe(24), 'adminPassword': secrets.token_urlsafe(24),
            'ticketKeys': json.dumps({'1': base64.b64encode(secrets.token_bytes(32)).decode()})})
    cfg = config()
    (PRIVATE / 'compose.env').write_text('\n'.join([
        f'PERF_DB_PASSWORD={cfg["dbPassword"]}', f'PERF_ROOT_PASSWORD={cfg["rootPassword"]}',
        f'PERF_MQ_PASSWORD={cfg["mqPassword"]}']) + '\n', encoding='utf-8')
    result = compose('up', '-d', '--pull', 'never', '--wait', '--wait-timeout', '180')
    if result.returncode:
        raise RuntimeError('专用 Compose 启动失败')
    with connect(False) as db, db.cursor() as cur:
        cur.execute('SHOW TABLES')
        tables = {next(iter(r.values())) for r in cur.fetchall()}
        cur.execute("SELECT COUNT(*) n FROM information_schema.tables WHERE table_schema='performance_control' AND table_name='environment_guard'")
        has_guard = cur.fetchone()['n'] > 0
        if tables and not has_guard:
            raise RuntimeError('已有无隔离标记数据，禁止接管')
        cur.execute('CREATE DATABASE IF NOT EXISTS performance_control')
        cur.execute('CREATE TABLE IF NOT EXISTS performance_control.environment_guard(token VARCHAR(64) PRIMARY KEY)')
        cur.execute('SELECT token FROM performance_control.environment_guard')
        guard = cur.fetchone()
        if guard and guard['token'] != cfg['guardToken']:
            raise RuntimeError('已有环境凭据不匹配，禁止接管')
        if not guard:
            cur.execute('INSERT INTO performance_control.environment_guard(token) VALUES(%s)', (cfg['guardToken'],))
    print('独立环境已就绪，未触及 ticketing 开发库')
    # 初始化基础设施不覆盖已经归档的历史机器/镜像基线；每轮套件独立保存环境。
    if not (RESULTS / 'hardware.json').exists():
        environment_snapshot()


def environment_snapshot():
    docker = json.loads(subprocess.check_output(['docker','info','--format','{{json .}}'], text=True))
    images = json.loads(subprocess.check_output(['docker','image','inspect','mysql:8.4','rabbitmq:4.1-management-alpine','redis:7-alpine'], text=True))
    host = {'platform':platform.platform(),'logicalProcessors':os.cpu_count()}
    if os.name == 'nt':
        command = "[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false); $o=Get-CimInstance Win32_OperatingSystem; $c=Get-CimInstance Win32_Processor; @{CPU=$c.Name;TotalMemoryGiB=[math]::Round($o.TotalVisibleMemorySize/1MB,2);Build=$o.BuildNumber} | ConvertTo-Json -Compress"
        host.update(json.loads(subprocess.check_output(['powershell','-NoProfile','-Command',command],encoding='utf-8')))
    binary = shutil.which('k6') or next(PRIVATE.glob('tools/**/k6.exe'), None)
    snapshot = {'capturedAt':datetime.now(timezone.utc).isoformat(),'host':host,
                'docker':{k:docker.get(k) for k in ['ServerVersion','NCPU','MemTotal','OperatingSystem','Driver']},
                'images':[{'id':i['Id'],'tags':i['RepoTags'],'digests':i['RepoDigests']} for i in images],
                'java':subprocess.run(['java','-version'],capture_output=True,text=True).stderr.strip(),
                'python':sys.version,'k6':subprocess.check_output([str(binary),'version'],text=True).strip() if binary else None,
                'jvmFlags':['-Xms512m','-Xmx1024m','-XX:+UseG1GC'],
                'mysql':rows('SELECT @@version version,@@innodb_flush_log_at_trx_commit flush_at_commit,@@sync_binlog sync_binlog,@@transaction_isolation default_isolation,@@max_connections max_connections,@@innodb_buffer_pool_size buffer_pool_bytes,@@log_bin log_bin'),
                'mysqlLockMetrics':rows("SELECT NAME,COUNT,STATUS FROM information_schema.innodb_metrics WHERE NAME IN ('lock_deadlocks','lock_timeouts')")}
    save(RESULTS/'hardware.json',snapshot)
    return snapshot


def prepare(mode, count, staff_checkin=False):
    if count < 1 or count > 500:
        raise RuntimeError('用户数量须为1至500')
    if staff_checkin and mode != 'checkin':
        raise RuntimeError('工作人员授权夹具仅用于核销场景')
    connect().close()  # 必须先验证标记，然后才能删除已知测试表中的行。
    # 暂停应用消除后台扫描对重置过程的影响；迁移、索引及标记均保留。
    app = json.loads((PRIVATE / 'app.json').read_text(encoding='utf-8'))
    app_stop()
    # 应用停止后清理上一轮持久消息，避免已删除夹具留下孤立事件。
    for queue in ['ticketing.waitlist.dispatch', 'ticketing.waitlist.dispatch.dead', 'ticketing.purchase.orders', 'ticketing.purchase.orders.dead']:
        response = requests.delete('http://127.0.0.1:25673/api/queues/%2F/' + queue + '/contents',
                                   auth=('ticketing_perf', config()['mqPassword']), timeout=10)
        if response.status_code not in (204,404):
            raise RuntimeError('专用RabbitMQ队列清理失败')
    with connect() as db, db.cursor() as cur:
        cur.execute("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('staff_session_permission','admin_audit_log','user_session_quota')")
        extra_tables = {row['TABLE_NAME'] if 'TABLE_NAME' in row else row['table_name'] for row in cur.fetchall()}
        if 'staff_session_permission' in extra_tables:
            cur.execute('DELETE FROM staff_session_permission')
        if 'user_session_quota' in extra_tables:
            cur.execute('DELETE FROM user_session_quota')
        for table in TABLES:
            # 历史基线JAR仍可运行，旧schema尚无V8请求表。
            if table == 'purchase_request':
                cur.execute("SELECT COUNT(*) n FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='purchase_request'")
                if not cur.fetchone()['n']:
                    continue
            if table == 'user_account' and 'admin_audit_log' in extra_tables:
                # 审计是只追加的历史事实；仅保留被引用操作人，禁止删除/停用触发器来清空审计。
                cur.execute('DELETE FROM user_account WHERE id NOT IN (SELECT operator_id FROM admin_audit_log)')
            else:
                cur.execute('DELETE FROM ' + table)
        cur.execute("SELECT COUNT(*) n FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='purchase_capacity'")
        if cur.fetchone()['n']:
            cur.execute('UPDATE purchase_capacity SET pending_count=0 WHERE id=1')
    app_start(app['jar'], rate_limit_mode=app.get('rateLimitMode', 'regression'), **app.get('overrides', {}))
    adm = admin()
    # 批量注册仍调用真实 BCrypt 注册接口，准备成本不计入稳定压测。
    users = [{'username': f'perf_u{i:04d}', 'password': secrets.token_urlsafe(20)} for i in range(count + 1)]
    admins = [{'username': f'perf_a{i:04d}', 'password': secrets.token_urlsafe(20)} for i in range(count)]
    def register(account):
        Client(account, register=True)
    with ThreadPoolExecutor(max_workers=8) as pool:
        list(pool.map(register, users + admins))
    if not staff_checkin:
        execute("UPDATE user_account SET role='ADMIN' WHERE username LIKE 'perf\\_a%%' AND username<>'perf_admin'")
    # 保持查询规模恒定，避免只有一条活动数据的失真查询基线。
    with connect() as db, db.cursor() as cur:
        cur.executemany("INSERT INTO activity(title,category,city,venue,summary,description,status,published_at) VALUES(%s,'CONCERT','北京','专用压测场馆','压测','隔离环境数据','PUBLISHED',UTC_TIMESTAMP(6))",
                        [(f'压测活动{i:04d}',) for i in range(1000)])
    activity = str(rows('SELECT MIN(id) id FROM activity')[0]['id'])
    now = datetime.now(timezone.utc)
    def target(index, stock):
        session = adm.request('POST', f'/api/v1/admin/activities/{activity}/sessions', {
            'title': f'压测场次{index}', 'saleStartsAt': (now-timedelta(hours=1)).isoformat(),
            'startsAt': (now+timedelta(minutes=40)).isoformat(), 'endsAt': (now+timedelta(hours=3)).isoformat()})
        sku = adm.request('POST', f'/api/v1/admin/sessions/{session["id"]}/tickets', {
            'name': '压测票档', 'priceCents': '12800', 'totalStock': stock,
            'saleStartsAt': (now-timedelta(minutes=30)).isoformat(), 'saleEndsAt': (now+timedelta(minutes=35)).isoformat(),
            'maxQuantityPerOrder': 4})
        adm.request('POST', f'/api/v1/admin/tickets/{sku["id"]}/publish', {'version': sku['version']})
        return {'activityId': activity, 'sessionId': session['id'], 'skuId': sku['id']}
    stock = 5 if mode == 'hot-scarce' else count if mode == 'waitlist' else 1000000
    main = target(0, stock)
    fixture = {'environment': 'ticketing-perf-only', 'baseUrl': BASE, 'runId': uuid.uuid4().hex,
               'target': main, 'users': users[:count], 'admins': admins,
               'admin': {'username': 'perf_admin', 'password': config()['adminPassword']}, 'mode': mode}
    if staff_checkin:
        fixture['staffCheckin'] = True
        for account in admins:
            user_id = rows('SELECT id FROM user_account WHERE username=%s', (account['username'],))[0]['id']
            adm.request('POST', f'/api/v1/admin/staff/{user_id}/promote')
            adm.request('POST', f'/api/v1/admin/staff/{user_id}/sessions/{main["sessionId"]}/grant')
    if mode == 'mixed':
        fixture['mixed'] = [main] + [target(i, 1000000) for i in range(1, 64)]
    if mode == 'waitlist':
        # 持票用户与候补用户分离，每人每场不超过4张。
        holders = admins
        def occupy(account):
            c = Client(account)
            return c.order(main['skuId'])['id']
        with ThreadPoolExecutor(max_workers=10) as pool:
            fixture['holderOrders'] = list(pool.map(occupy, holders))
        # 有序申请确定 FIFO；分配阶段由真实 Outbox/RabbitMQ 触发。
        fixture['waitlistIds'] = [Client(u).request('POST', '/api/v1/waitlist', {
            'skuId': main['skuId'], 'quantity': 1})['id'] for u in fixture['users']]
    if mode == 'checkin':
        buyer = Client(users[0]); order = buyer.order(main['skuId'])
        buyer.request('POST', f'/api/v1/orders/{order["id"]}/pay')
        fixture['ticketId'] = buyer.request('GET', '/api/v1/tickets?size=1')['items'][0]['id']
    save(PRIVATE / 'input.json', fixture)
    print(f'等价数据准备完成 场景 {mode} 用户 {count} 活动1000 场次{64 if mode=="mixed" else 1}')
    return fixture


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


def db_snapshot():
    with connect() as db, db.cursor() as cur:
        cur.execute("SHOW GLOBAL STATUS WHERE Variable_name IN ('Threads_connected','Threads_running','Innodb_row_lock_waits','Innodb_row_lock_time','Innodb_deadlocks','Slow_queries','Questions','Com_select','Com_commit','Com_rollback')")
        status = {r['Variable_name']: int(r['Value']) for r in cur.fetchall()}
        cur.execute("SELECT NAME,COUNT FROM information_schema.innodb_metrics WHERE NAME IN('lock_deadlocks','lock_timeouts') AND STATUS='enabled'")
        status.update({r['NAME']:int(r['COUNT']) for r in cur.fetchall()})
        cur.execute("SELECT COUNT(*) n FROM performance_schema.data_lock_waits")
        status['current_lock_waits'] = cur.fetchone()['n']
        cur.execute("SELECT status,COUNT(*) n,MAX(retry_count) attempts FROM outbox_event GROUP BY status")
        outbox = cur.fetchall()
        cur.execute("SELECT COUNT(*) n,AVG(TIMESTAMPDIFF(MICROSECOND,created_at,sent_at))/1000 avg_ms,MAX(TIMESTAMPDIFF(MICROSECOND,created_at,sent_at))/1000 max_ms FROM outbox_event WHERE status='SENT'")
        lag = cur.fetchone()
        cur.execute("SELECT event_type,status,COUNT(*) n FROM outbox_event GROUP BY event_type,status")
        channels = cur.fetchall()
        cur.execute("""SELECT event_type,COUNT(*) n,
            AVG(TIMESTAMPDIFF(MICROSECOND,created_at,sent_at))/1000 avg_ms,
            MAX(TIMESTAMPDIFF(MICROSECOND,created_at,sent_at))/1000 max_ms
            FROM outbox_event WHERE status='SENT' GROUP BY event_type""")
        channel_lag = cur.fetchall()
        cur.execute("""SELECT event_type,COUNT(*) n,
            MAX(TIMESTAMPDIFF(MICROSECOND,created_at,UTC_TIMESTAMP(6)))/1000 oldest_ms
            FROM outbox_event WHERE status IN('PENDING','PROCESSING') GROUP BY event_type""")
        backlog = cur.fetchall()
        cur.execute("SELECT COUNT(*) n FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='purchase_request'")
        purchase = []
        if cur.fetchone()['n']:
            cur.execute("SELECT status,COALESCE(failure_code,'NONE') failure_code,COUNT(*) n FROM purchase_request GROUP BY status,failure_code")
            purchase = cur.fetchall()
        return {'mysql': status, 'outbox': outbox, 'outboxLag': lag, 'outboxChannels': channels,
                'outboxChannelLag': channel_lag, 'outboxBacklogAge': backlog, 'purchaseRequests': purchase}


def diagnostics():
    with connect() as db, db.cursor() as cur:
        cur.execute("""SELECT DIGEST_TEXT sql_pattern,COUNT_STAR calls,ROUND(SUM_TIMER_WAIT/1000000000,3) total_ms,
                    ROUND(AVG_TIMER_WAIT/1000000000,3) avg_ms,SUM_ROWS_EXAMINED rows_examined,SUM_SORT_ROWS sort_rows
                    FROM performance_schema.events_statements_summary_by_digest
                    WHERE SCHEMA_NAME='ticketing_perf' AND DIGEST_TEXT IS NOT NULL ORDER BY SUM_TIMER_WAIT DESC LIMIT 25""")
        digest = cur.fetchall()
        cur.execute("SELECT o.user_id,o.session_id,i.sku_id FROM ticket_order o JOIN ticket_order_item i ON i.order_id=o.id ORDER BY (o.status='PAID') DESC,o.id LIMIT 1")
        source = cur.fetchone()
        if source is None:
            cur.execute('SELECT (SELECT MIN(id) FROM user_account WHERE role=\'USER\') user_id,t.session_id,t.id sku_id FROM ticket_sku t ORDER BY t.id LIMIT 1')
            source = cur.fetchone()
        source = source or {'user_id':0,'session_id':0,'sku_id':0}
        user_id, session_id, sku_id = (int(source[k] or 0) for k in ['user_id','session_id','sku_id'])
        plans = {}
        for name, sql in {
            'quota': f"SELECT COALESCE(SUM(i.quantity),0) FROM ticket_order o JOIN ticket_order_item i ON i.order_id=o.id WHERE o.user_id={user_id} AND o.session_id={session_id} AND o.status IN ('PENDING','PAID')",
            'fifo': f"SELECT * FROM waitlist_entry WHERE sku_id={sku_id} AND status='WAITING' ORDER BY id LIMIT 1",
            'outbox': "SELECT * FROM outbox_event WHERE event_type='WAITLIST_DISPATCH_REQUESTED' AND status IN ('PENDING','PROCESSING') AND next_retry_at<=UTC_TIMESTAMP(6) ORDER BY next_retry_at,id LIMIT 1",
            'orders_page': f"SELECT o.*,i.sku_id,i.quantity,i.unit_price_cents,i.sku_name_snapshot,s.title,s.starts_at,s.status,a.id,a.title,a.status,p.payment_no,p.amount_cents,p.paid_at FROM ticket_order o JOIN ticket_order_item i ON i.order_id=o.id JOIN activity_session s ON s.id=o.session_id JOIN activity a ON a.id=s.activity_id LEFT JOIN payment_record p ON p.order_id=o.id AND p.status='SUCCEEDED' WHERE o.user_id={user_id} ORDER BY o.created_at DESC,o.id DESC LIMIT 10 OFFSET 0",
            'waitlist_page': f"SELECT w.*,t.name,t.price_cents,s.id,s.title,a.id,a.title,o.status,o.expires_at FROM waitlist_entry w JOIN ticket_sku t ON t.id=w.sku_id JOIN activity_session s ON s.id=t.session_id JOIN activity a ON a.id=s.activity_id LEFT JOIN ticket_order o ON o.id=w.reserved_order_id AND o.user_id=w.user_id AND o.session_id=s.id AND EXISTS(SELECT 1 FROM ticket_order_item i WHERE i.order_id=o.id AND i.sku_id=w.sku_id AND i.quantity=w.quantity) WHERE w.user_id={user_id} ORDER BY w.created_at DESC,w.id DESC LIMIT 10 OFFSET 0",
            'tickets': f'SELECT * FROM electronic_ticket WHERE owner_id={user_id} ORDER BY created_at DESC,id DESC LIMIT 10',
            'activities': "SELECT * FROM activity WHERE status='PUBLISHED' ORDER BY published_at DESC,id DESC LIMIT 20 OFFSET 0",
        }.items():
            cur.execute('EXPLAIN ' + sql); plans[name] = cur.fetchall()
        cur.execute("SELECT COUNT(*) n,ROUND(COALESCE(SUM(TIME_TO_SEC(query_time)),0)*1000,3) total_ms FROM mysql.slow_log WHERE db='ticketing_perf'")
        slow = cur.fetchone()
    return {'statementDigests': digest, 'explain': plans, 'planParameters':source,
            'planScope':'测试结束后的实际夹具参数，不外推生产行数或压力中间状态', 'slowQueries': slow}


METRICS = ['process.cpu.usage', 'jvm.memory.used', 'jvm.gc.pause', 'jvm.threads.live',
           'hikaricp.connections.active', 'hikaricp.connections.pending', 'hikaricp.connections.max',
           'hikaricp.connections.acquire', 'hikaricp.connections.timeout',
           'hikaricp.connections.usage', 'spring.rabbitmq.listener',
           'ticketing.catalog.loads.active', 'ticketing.catalog.local.entries',
           'ticketing.catalog.redis.duration', 'ticketing.purchase.admission.redis.duration']
METRICS += ['ticketing.trade.transaction.duration', 'ticketing.trade.lock.duration', 'ticketing.trade.quota']


def mq_snapshot():
    response = requests.get('http://127.0.0.1:25673/api/queues/%2F',
                            auth=('ticketing_perf', config()['mqPassword']), timeout=3)
    response.raise_for_status()
    return [{key: q.get(key) for key in ['name', 'messages_ready', 'messages_unacknowledged',
                                        'consumers', 'message_stats']} for q in response.json()]


def sample(client):
    result = {'elapsedUtc': datetime.now(timezone.utc).isoformat(), **db_snapshot()}
    result['jvm'] = {}
    for name in METRICS:
        params = {'tag': 'area:heap'} if name == 'jvm.memory.used' else None
        response = client.session.get(BASE + MONITOR + '/metrics/' + name, params=params, timeout=10)
        if response.status_code == 200:
            result['jvm'][name] = response.json().get('measurements')
        elif response.status_code != 404:
            response.raise_for_status()
    try:
        result['rabbitmq'] = mq_snapshot()
    except requests.RequestException:
        result['rabbitmqUnavailable'] = True
    return result


def run(args):
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,63}', args.label):
        raise RuntimeError('报告标签只允许字母数字下划线和连字符')
    fixture = json.loads((PRIVATE / 'input.json').read_text(encoding='utf-8'))
    supplied_ids = [getattr(args, key, None) for key in ['activity_id','session_id','sku_id']]
    if any(supplied_ids):
        if args.scenario not in ['query','hot-ample','hot-scarce']:
            raise RuntimeError('自定义单目标ID仅用于查询或热点场景；关联交易场景须使用prepare生成的完整夹具')
        if not all(supplied_ids) or not all(re.fullmatch(r'[1-9][0-9]{0,18}', value) for value in supplied_ids):
            raise RuntimeError('自定义活动场次票档须同时提供合法整数ID')
        activity_id, session_id, sku_id = supplied_ids
        if not rows('SELECT t.id FROM ticket_sku t JOIN activity_session s ON s.id=t.session_id WHERE t.id=%s AND s.id=%s AND s.activity_id=%s', (sku_id,session_id,activity_id)):
            raise RuntimeError('指定票档不属于专用环境中的该场次和活动')
        fixture['target'] = {'activityId':activity_id,'sessionId':session_id,'skuId':sku_id}
    if fixture['mode'] != args.scenario or args.vus > len(fixture['users']):
        raise RuntimeError('先为当前场景准备足够的独立测试用户')
    if fixture.get('mode') == 'waitlist' and args.vus != len(fixture['users']):
        raise RuntimeError('候补测试要求候补人数与并发数一致')
    connect().close()
    output = RESULTS / args.label / f'{args.scenario}-{args.vus}'
    if output.exists():
        raise RuntimeError('结果目录已存在，请使用新标签保留原始数据')
    output.mkdir(parents=True)
    with connect() as db, db.cursor() as cur:
        cur.execute('TRUNCATE performance_schema.events_statements_summary_by_digest')
        cur.execute('TRUNCATE mysql.slow_log')
    initial = db_snapshot()
    stop = threading.Event()
    samples = []
    failures = []
    def monitor():
        client = admin()
        while not stop.is_set():
            try:
                samples.append(sample(client))
            except Exception as error:
                failures.append(type(error).__name__)
            stop.wait(2)
    # 先完成首轮指标初始化，避免 JVM 首次指标探测混入压力窗口。
    samples.append(sample(admin()))
    worker = threading.Thread(target=monitor, daemon=True); worker.start()
    release_result = {}
    def release():
        if stop.wait(args.warmup + 1):
            return
        started = time.monotonic()
        try:
            def cancel(pair):
                account, order_id = pair
                return Client(account).request('POST', f'/api/v1/orders/{order_id}/cancel')
            with ThreadPoolExecutor(max_workers=10) as pool:
                list(pool.map(cancel, zip(fixture['admins'], fixture['holderOrders'])))
            release_result['releasedOrders'] = len(fixture['holderOrders'])
            release_result['releaseSeconds'] = time.monotonic() - started
        except Exception as error:
            release_result['failure'] = type(error).__name__
    release_thread = None
    if args.scenario == 'waitlist':
        release_thread = threading.Thread(target=release, daemon=True); release_thread.start()
    env = os.environ.copy()
    save(PRIVATE / 'run-input.json', fixture)
    env.update(PERF_INPUT=str(PRIVATE / 'run-input.json'), PERF_SCENARIO=args.scenario,
               PERF_VUS=str(args.vus), PERF_DURATION=str(args.duration), PERF_WARMUP=str(args.warmup),
               PERF_SUMMARY=str(output / 'k6-summary.json'))
    env['PERF_ADMISSION_EXPECTED_REJECTION'] = 'true' if getattr(args, 'admission_rejections', False) else 'false'
    binary = args.k6 or shutil.which('k6') or next(PRIVATE.glob('tools/**/k6.exe'), None)
    if not binary:
        raise RuntimeError('未发现k6，请用 --k6 指定本机可执行文件')
    try:
        process = subprocess.run([str(binary), 'run', '--quiet', str(HERE / 'scenarios.js')],
                                 cwd=ROOT, env=env, capture_output=True, text=True, encoding='utf-8', errors='replace',
                                 timeout=args.duration + args.warmup + 210)
    finally:
        stop.set(); worker.join(timeout=15)
        if release_thread: release_thread.join(timeout=30)
    # Outbox异步确认后再审计；不直接修改订单或库存。
    for _ in range(30):
        if not rows("SELECT id FROM outbox_event WHERE status IN ('PENDING','PROCESSING') LIMIT 1"):
            break
        time.sleep(1)
    final = db_snapshot()
    summary = json.loads((output / 'k6-summary.json').read_text(encoding='utf-8'))
    metrics = summary['metrics']
    if metrics.get('steady_requests', {}).get('values', {}).get('count', 0) < 1:
        raise RuntimeError('稳定阶段没有真实请求，测试无效')
    if args.scenario == 'checkin' and metrics.get('checked_tickets', {}).get('values', {}).get('count', 0) != 1:
        raise RuntimeError('同票竞争必须且只能核销成功一次')
    if args.scenario == 'checkin' and rows("SELECT COUNT(*) n FROM electronic_ticket WHERE id=%s AND status='USED' AND used_at IS NOT NULL AND checked_by IS NOT NULL", (fixture['ticketId'],))[0]['n'] != 1:
        raise RuntimeError('核销成功响应未对应真实USED记录')
    if args.scenario == 'waitlist':
        placeholders = ','.join(['%s'] * len(fixture['waitlistIds']))
        completed = rows(f"SELECT COUNT(*) n FROM waitlist_entry WHERE id IN ({placeholders}) AND status='COMPLETED'", fixture['waitlistIds'])[0]['n']
        if completed != args.vus:
            raise RuntimeError('候补队列没有在压力窗口内全部分配并支付，不得视为闭环成功')
    metadata = {'environment': 'ticketing-perf-only', 'baseCommit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip(),
                'scenario': args.scenario, 'vus': args.vus, 'warmupSeconds': args.warmup, 'steadySeconds': args.duration,
                'gracefulStopSeconds': 150, 'outboxDrainMaxSeconds': 30,
                'data': {'activities': 1000, 'sessions': 64 if args.scenario=='mixed' else 1, 'users': args.vus},
                'app': json.loads((PRIVATE / 'app.json').read_text(encoding='utf-8')), 'k6ExitCode': process.returncode,
                'initial': initial, 'final': final, 'mysqlDelta': {k: final['mysql'][k]-v for k,v in initial['mysql'].items()},
                'monitorFailures': failures, 'release': release_result, 'audit': audit()}
    save(output / 'environment.json', metadata)
    save(output / 'monitoring.json', samples)
    save(output / 'database.json', diagnostics())
    # 进程诊断只保存在私有目录；共享报告不复制 k6 的潜在请求日志。
    (PRIVATE / 'last-k6.log').write_text(process.stdout + process.stderr, encoding='utf-8')
    if process.returncode:
        raise RuntimeError(f'k6验证未通过 退出码{process.returncode}，查看私有日志')
    if release_result.get('failure') or failures:
        raise RuntimeError('释放或监测失败，不得将结果视为通过')
    print(f'压测及数据库审计通过 {args.label} {args.scenario} {args.vus}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    commands.add_parser('init')
    start = commands.add_parser('start'); start.add_argument('--jar', required=True)
    start.add_argument('--pool-size', type=int)
    start.add_argument('--outbox-batch-size', type=int)
    start.add_argument('--rate-limit-mode', choices=['baseline','regression','production'], default='regression',
                       help='仅专用环境 baseline关闭对照 regression开启放宽阈值 production默认阈值')
    commands.add_parser('stop')
    commands.add_parser('environment')
    prepare_cmd = commands.add_parser('prepare')
    prepare_cmd.add_argument('--scenario', choices=['query','hot-ample','hot-scarce','mixed','waitlist','checkin'], required=True)
    prepare_cmd.add_argument('--users', type=int, default=100)
    prepare_cmd.add_argument('--staff-checkin', action='store_true', help='通过真实管理员API准备指定场次的核销工作人员')
    run_cmd = commands.add_parser('run')
    run_cmd.add_argument('--scenario', choices=['query','hot-ample','hot-scarce','mixed','waitlist','checkin'], required=True)
    run_cmd.add_argument('--vus', type=int, default=10); run_cmd.add_argument('--duration', type=int, default=20)
    run_cmd.add_argument('--warmup', type=int, default=10); run_cmd.add_argument('--label', required=True)
    run_cmd.add_argument('--k6')
    for name in ['activity','session','sku']:
        run_cmd.add_argument('--' + name + '-id')
    commands.add_parser('audit')
    args = parser.parse_args()
    if args.command == 'init': initialize()
    elif args.command == 'start':
        overrides = {}
        if args.pool_size is not None: overrides['SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE'] = str(args.pool_size)
        if args.outbox_batch_size is not None: overrides['TICKETING_WAITLIST_OUTBOX_BATCH_SIZE'] = str(args.outbox_batch_size)
        app_start(args.jar, rate_limit_mode=args.rate_limit_mode, **overrides)
    elif args.command == 'stop': app_stop()
    elif args.command == 'environment': environment_snapshot();print('环境快照已保存')
    elif args.command == 'prepare': prepare(args.scenario, args.users, args.staff_checkin)
    elif args.command == 'run': run(args)
    elif args.command == 'audit': print(json.dumps(audit(), ensure_ascii=False, default=str))


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        # 库连接异常可能带用户名/地址但不应输出全部请求或秘密。
        print(f'压测工具停止 {type(error).__name__} {str(error) if isinstance(error, RuntimeError) else "请检查专用环境及私有日志"}', file=sys.stderr)
        sys.exit(1)
