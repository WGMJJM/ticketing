#!/usr/bin/env python3
"""本机隔离购票诊断：预登录、JMeter仅POST、失败关闭的资源熔断和只读对账。"""
from __future__ import annotations

import argparse
import concurrent.futures
import csv
import ctypes
from ctypes import wintypes
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import sys
import threading
import time

import pymysql
import requests
from diagnosis_stats import SafetyGate, summarize, quantiles, has_persistent_pending_growth

ROOT = Path(__file__).resolve().parents[2]
PLAN = Path(__file__).with_name('purchase-post-only.jmx')
spec = importlib.util.spec_from_file_location('prepare_data', Path(__file__).with_name('prepare-data.py'))
prepare = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prepare)
GIB = 1024 ** 3


def save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2, default=str), encoding='utf-8')


def completed_workers(snapshot):
    return sum(m['count'] for m in snapshot['metrics']
               if m['name'] == 'ticketing.diagnostics.duration'
               and m['tags'] == {'role': 'consumer', 'stage': 'worker.process'})


def publish_flow(path, value):
    temporary = path.with_suffix('.tmp')
    save(temporary, value)
    # Windows读取者短暂持有不允许删除的句柄；最多重试100ms，仍失败则保持失败关闭。
    for attempt in range(10):
        try:
            os.replace(temporary, path)
            return
        except PermissionError:
            if attempt == 9: raise
            time.sleep(.01)


class CompletionFeed:
    """只读本机内存指标，不查询业务状态、不借数据库连接；完成多少才补多少。"""
    def __init__(self, directory, monitor, baseline):
        self.directory, self.monitor, self.baseline = directory, monitor, baseline
        self.done = threading.Event()
        self.thread = None
        self.completed = {}

    def start(self):
        self.thread = threading.Thread(target=self.loop, daemon=True)
        self.thread.start()

    def loop(self):
        try:
            with (self.directory / 'completion-feed.jsonl').open('w', encoding='utf-8') as out:
                while not self.done.is_set():
                    began = time.monotonic()
                    snapshot = diag()
                    now = int(time.time()*1000)
                    terminal = snapshot['completions']
                    for key,row in terminal['recent'].items():
                        if key not in self.completed:
                            self.completed[key] = dict(row, observerMs=now)
                    value = dict(timeMs=now, completed=terminal['count']-self.baseline,
                                 terminal=terminal['recent'],
                                 observerMs=(time.monotonic()-began)*1000)
                    publish_flow(self.directory / 'flow.json', value)
                    out.write(json.dumps(value)+'\n'); out.flush()
                    self.done.wait(max(0, .05-(time.monotonic()-began)))
        except Exception as error:
            self.monitor.trip('completion_feed_failure_'+type(error).__name__)

    def close(self):
        self.done.set()
        if self.thread: self.thread.join(4)
        save(self.directory/'completed-requests.json', self.completed)


def command(args, timeout=30):
    return subprocess.run([str(x) for x in args], cwd=ROOT, check=True, capture_output=True,
                          text=True, encoding='utf-8', errors='replace', timeout=timeout).stdout.strip()


class MemoryStatus(ctypes.Structure):
    _fields_ = [('length', wintypes.DWORD), ('load', wintypes.DWORD)] + [
        (key, ctypes.c_ulonglong) for key in ('total', 'available', 'pageTotal', 'pageAvailable', 'virtual', 'virtualAvailable', 'extended')]


class ProcessMemory(ctypes.Structure):
    _fields_ = [('cb', wintypes.DWORD), ('faults', wintypes.DWORD)] + [
        (key, ctypes.c_size_t) for key in ('peakWorking', 'working', 'peakPaged', 'paged', 'peakNonPaged', 'nonPaged', 'page', 'peakPage')]


class WindowsResources:
    def __init__(self):
        if sys.platform != 'win32':
            raise RuntimeError('资源熔断实现仅支持Windows；不允许无监控运行')
        self.kernel = ctypes.WinDLL('kernel32', use_last_error=True)
        self.psapi = ctypes.WinDLL('psapi', use_last_error=True)
        self.kernel.OpenProcess.restype = wintypes.HANDLE
        self.kernel.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
        self.kernel.CloseHandle.argtypes = [wintypes.HANDLE]
        self.kernel.GetProcessTimes.argtypes = [wintypes.HANDLE] + [ctypes.POINTER(wintypes.FILETIME)] * 4
        self.psapi.GetProcessMemoryInfo.argtypes = [wintypes.HANDLE, ctypes.POINTER(ProcessMemory), wintypes.DWORD]
        self.previous = None
        self.processes = {}

    def host(self):
        values = [wintypes.FILETIME() for _ in range(3)]
        if not self.kernel.GetSystemTimes(*[ctypes.byref(x) for x in values]):
            raise OSError('读取主机CPU失败')
        idle, kernel, user = [x.dwLowDateTime + (x.dwHighDateTime << 32) for x in values]
        total = kernel + user
        cpu = 0.0 if self.previous is None else 100 * (1 - (idle - self.previous[0]) / max(1, total - self.previous[1]))
        self.previous = idle, total
        memory = MemoryStatus(); memory.length = ctypes.sizeof(memory)
        if not self.kernel.GlobalMemoryStatusEx(ctypes.byref(memory)):
            raise OSError('读取物理内存失败')
        return dict(timeMs=int(time.time() * 1000), monotonic=time.monotonic(), cpu=cpu,
                    totalGiB=memory.total / GIB, freeGiB=memory.available / GIB,
                    ramUsedPercent=100 * (1 - memory.available / memory.total))

    def process(self, pid):
        handle = self.kernel.OpenProcess(0x410, False, pid)
        if not handle:
            raise OSError('读取受控进程失败')
        try:
            times = [wintypes.FILETIME() for _ in range(4)]
            if not self.kernel.GetProcessTimes(handle, *[ctypes.byref(x) for x in times]):
                raise OSError('读取进程CPU失败')
            ticks = sum(x.dwLowDateTime + (x.dwHighDateTime << 32) for x in times[2:])
            memory = ProcessMemory(); memory.cb = ctypes.sizeof(memory)
            if not self.psapi.GetProcessMemoryInfo(handle, ctypes.byref(memory), memory.cb):
                raise OSError('读取进程工作集失败')
            now = time.monotonic(); old = self.processes.get(pid)
            cpu = (ticks - old[0]) / 1e7 / max(.001, now - old[1]) * 100 / os.cpu_count() if old else 0
            self.processes[pid] = ticks, now
            return dict(pid=pid, cpuPercentHost=cpu, workingMiB=memory.working / 1024 ** 2, cpuSeconds=ticks / 1e7)
        finally:
            self.kernel.CloseHandle(handle)


def config(runtime):
    path = runtime / 'compose.env'
    env = prepare.read_env(path)
    expected = {'MYSQL_HOST': '127.0.0.1', 'MYSQL_PORT': '13307', 'MYSQL_DATABASE': 'ticketingjmeter',
                'REDIS_PORT': '16379', 'RABBITMQ_PORT': '15673', 'RABBITMQ_MANAGEMENT_PORT': '15674', 'SERVER_PORT': '18080'}
    if any(env.get(key) != value for key, value in expected.items()) or not env.get('JMETER_GUARD_TOKEN'):
        raise RuntimeError('专用配置地址、schema或guard不符')
    return env


def docker_guard(runtime):
    env = config(runtime)
    evidence = []
    for service, dest, port in [('mysql', '/var/lib/mysql', '13307'), ('redis', '/data', '16379'), ('rabbitmq', '/var/lib/rabbitmq', '15673')]:
        name = f'ticketing-jmeter-{service}-1'
        item = json.loads(command(['docker', 'inspect', name]))[0]
        labels = item['Config']['Labels']
        if labels.get('com.docker.compose.project') != 'ticketing-jmeter' or labels.get('com.docker.compose.service') != service:
            raise RuntimeError('隔离项目标签不符')
        mounts = [m for m in item['Mounts'] if m['Destination'] == dest]
        if len(mounts) != 1 or mounts[0]['Type'] != 'volume' or not mounts[0]['Name'].startswith('ticketing-jmeter_'):
            raise RuntimeError('隔离卷不符')
        bindings = [b for values in item['HostConfig']['PortBindings'].values() for b in (values or [])]
        if not any(b['HostIp'] == '127.0.0.1' and b['HostPort'] == port for b in bindings):
            raise RuntimeError('隔离容器端口不符')
        if not item['State']['Running']:
            raise RuntimeError('隔离容器未运行')
        evidence.append(dict(name=name, volume=mounts[0]['Name'], image=item['Config']['Image'], state=item['State']['Status']))
    return env, evidence


def db_connect(env):
    if env.get('MYSQL_PORT') != '13307' or env.get('MYSQL_DATABASE') != 'ticketingjmeter':
        raise RuntimeError('拒绝连接非隔离库')
    return pymysql.connect(host='127.0.0.1', port=13307, user='root', password=env['MYSQL_ROOT_PASSWORD'],
                           database='ticketingjmeter', charset='utf8mb4', autocommit=True,
                           read_timeout=4, write_timeout=4, cursorclass=pymysql.cursors.DictCursor)


def db_guard(db, env):
    with db.cursor() as cur:
        cur.execute('SELECT DATABASE() n')
        if cur.fetchone()['n'] != 'ticketingjmeter':
            raise RuntimeError('数据库实际schema不符')
        cur.execute('SELECT token_sha256 FROM jmeter_environment_guard WHERE id=1')
        if cur.fetchone()['token_sha256'] != hashlib.sha256(env['JMETER_GUARD_TOKEN'].encode()).hexdigest():
            raise RuntimeError('数据库随机guard不符')


def redis(*args):
    return command(['docker', 'exec', 'ticketing-jmeter-redis-1', 'redis-cli', '--json', *map(str, args)], timeout=5)


def queue(env):
    response = requests.get('http://127.0.0.1:15674/api/queues/%2F/ticketing.purchase.orders',
                            auth=(env['RABBITMQ_USER'], env['RABBITMQ_PASSWORD']), timeout=2)
    response.raise_for_status(); data = response.json()
    stats = data.get('message_stats', {})
    return dict(ready=data.get('messages_ready', 0), unacked=data.get('messages_unacknowledged', 0),
                consumers=data.get('consumers', 0), ack=stats.get('ack', 0),
                ackRate=stats.get('ack_details', {}).get('rate', 0),
                delivered=stats.get('deliver_get', 0), published=stats.get('publish', 0),
                redelivered=stats.get('redeliver', 0))


def diag():
    response = requests.get('http://127.0.0.1:18081/actuator/purchaseDiagnostics', timeout=2)
    response.raise_for_status()
    return response.json()


def database_sample(db):
    with db.cursor() as cur:
        cur.execute("SHOW GLOBAL STATUS WHERE Variable_name IN ('Threads_running','Threads_connected','Innodb_row_lock_time','Innodb_row_lock_waits','Innodb_deadlocks')")
        status = {r['Variable_name']: int(r['Value']) for r in cur.fetchall()}
        cur.execute("SELECT COUNT FROM information_schema.INNODB_METRICS WHERE NAME='lock_deadlocks'")
        status['Innodb_deadlocks'] = int(cur.fetchone()['COUNT'])
        cur.execute("""SELECT r.OBJECT_NAME,r.INDEX_NAME,r.LOCK_DATA,r.LOCK_MODE,
                       b.OBJECT_NAME blocking_table,b.LOCK_DATA blocking_data
                       FROM performance_schema.data_lock_waits w
                       JOIN performance_schema.data_locks r ON r.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID
                       JOIN performance_schema.data_locks b ON b.ENGINE_LOCK_ID=w.BLOCKING_ENGINE_LOCK_ID
                       WHERE r.OBJECT_SCHEMA='ticketingjmeter' LIMIT 20""")
        return dict(status=status, waits=cur.fetchall())


def digest(db):
    with db.cursor() as cur:
        cur.execute("""SELECT DIGEST,DIGEST_TEXT,COUNT_STAR,SUM_TIMER_WAIT,MAX_TIMER_WAIT,SUM_LOCK_TIME,
                       SUM_ERRORS,SUM_ROWS_EXAMINED FROM performance_schema.events_statements_summary_by_digest
                       WHERE SCHEMA_NAME='ticketingjmeter' AND DIGEST IS NOT NULL""")
        return cur.fetchall()


class Monitor:
    def __init__(self, env, directory, backend_pid=None, sku_id=None):
        self.env, self.directory, self.backend_pid = env, directory, backend_pid
        self.jmeter_pid = None
        self.stop = directory / 'stop.flag'
        self.done = threading.Event()
        self.gate = SafetyGate()
        self.samples = []
        self.failure = None
        self.thread = None
        self.fuse_after = None
        self.jmeter_process = None
        self.sku_id = sku_id
        self.measurement_start_ms = None

    def trip(self, reason):
        if not self.stop.exists():
            save(self.stop, dict(reason=reason, timeMs=int(time.time() * 1000)))
        self.failure = self.failure or reason

    def start(self):
        self.thread = threading.Thread(target=self.loop, daemon=True)
        self.thread.start()

    def loop(self):
        native = WindowsResources()
        try:
            with db_connect(self.env) as db, (self.directory / 'resources.jsonl').open('a', encoding='utf-8') as out:
                db_guard(db, self.env)
                while not self.done.is_set():
                    began = time.monotonic()
                    sample = native.host()
                    if self.backend_pid:
                        sample['backend'] = native.process(self.backend_pid)
                        sample['diagnostics'] = diag()
                        if sample['backend']['workingMiB'] > 1024:
                            sample['unsafe'] = 'backend_working_set_over_1GiB'
                    if self.jmeter_pid and self.jmeter_process and self.jmeter_process.poll() is None:
                        try:
                            sample['jmeter'] = native.process(self.jmeter_pid)
                        except OSError:
                            if self.jmeter_process.poll() is None: raise
                    if 'jmeter' in sample:
                        if sample['jmeter']['workingMiB'] > 768:
                            sample['unsafe'] = 'jmeter_working_set_over_768MiB'
                    sample['mq'] = queue(self.env)
                    sample['mysql'] = database_sample(db)
                    # 每轮均采容器内存；命令典型耗时约1秒，超过5秒将失败关闭。
                    rows = command(['docker', 'stats', '--no-stream', '--format', '{{json .}}',
                                    'ticketing-jmeter-mysql-1', 'ticketing-jmeter-redis-1', 'ticketing-jmeter-rabbitmq-1'], timeout=4)
                    sample['docker'] = [json.loads(row) for row in rows.splitlines()]
                    sample['dockerMemoryPercentSum'] = sum(float(row['MemPerc'].strip('%')) for row in sample['docker'])
                    if any(float(row['MemPerc'].strip('%')) > 85 for row in sample['docker']):
                        sample['unsafe'] = 'container_memory_over_85'
                    if sample['dockerMemoryPercentSum'] > 85:
                        sample['unsafe'] = 'isolated_containers_memory_sum_over_85'
                    capacity = int(sample.get('diagnostics', {}).get('capacity', 200))
                    ledger = json.loads(redis('EVAL', "return {redis.call('GET',KEYS[1]),redis.call('HGETALL',KEYS[2])}", 2,
                        'ticketing:seckill:v2:ticketingjmeter:pending', f'ticketing:seckill:v2:ticketingjmeter:sku:{self.sku_id}'))
                    sample['redisPending'] = int(ledger[0] or 0)
                    sample['stockLedger'] = ledger[1] if isinstance(ledger[1], dict) else dict(zip(ledger[1][::2], ledger[1][1::2]))
                    if sample['redisPending'] >= capacity * .75:
                        sample['unsafe'] = 'redis_pending_reaches_75_percent_capacity'
                    if sample['mq']['ready'] + sample['mq']['unacked'] >= capacity * .75:
                        sample['unsafe'] = 'queue_reaches_75_percent_capacity'
                    if self.backend_pid:
                        heap = sample['diagnostics']['heap']
                        if heap['usedBytes'] > heap['maxBytes'] * .85:
                            sample['unsafe'] = 'backend_heap_over_85_percent'
                    recent = [s for s in self.samples if sample['monotonic'] - s['monotonic'] <= 12]
                    pressure = [s for s in recent if self.measurement_start_ms is not None
                                and s['timeMs'] >= self.measurement_start_ms + 2000]
                    # Rabbit管理统计有数秒滞后，闭环初次填充会被误判为持续增长。
                    # 用同次采样的Redis实时未完成数判定持续增长，MQ曲线仍完整保留。
                    if has_persistent_pending_growth(pressure):
                        sample['unsafe'] = 'persistent_queue_growth'
                    if recent and self.backend_pid:
                        def gc_ms(s):
                            return sum(m.get('sumMs', 0) for m in s['diagnostics']['metrics'] if m['name'] == 'jvm.gc.pause')
                        span = sample['monotonic'] - recent[0]['monotonic']
                        if span >= 8 and gc_ms(sample) - gc_ms(recent[0]) > span * 100:
                            sample['unsafe'] = 'gc_pause_over_10_percent'
                    if self.fuse_after and self.jmeter_pid:
                        path = self.directory / 'post.jtl'
                        if path.exists():
                            with path.open(encoding='utf-8-sig', newline='') as stream:
                                if sum(1 for _ in csv.DictReader(stream)) >= self.fuse_after:
                                    sample['unsafe'] = 'selftest_stop_after_five_posts'
                    elif self.jmeter_pid:
                        path = self.directory / 'post.jtl'
                        if path.exists():
                            with path.open(encoding='utf-8-sig', newline='') as stream:
                                all_posts = list(csv.DictReader(stream))
                                last_posts = all_posts[-20:]
                            if len(last_posts) >= 10 and sum(p['responseCode'] != '202' for p in last_posts) >= 5:
                                sample['unsafe'] = 'five_rejections_in_last_twenty_posts'
                            if sum(p['responseCode'] == 'NETWORK_ERROR' for p in last_posts) >= 2:
                                sample['unsafe'] = 'repeated_network_failure'
                            recent_posts = [p for p in all_posts if int(p['postStartMs']) >= sample['timeMs']-4000]
                            rejected = sum(p['responseCode'] != '202' for p in recent_posts)
                            if rejected >= 5 and rejected / len(recent_posts) >= .05:
                                sample['unsafe'] = 'recent_rejections_at_least_five_and_5_percent'
                    reason = self.gate.update(sample)
                    self.samples.append(sample)
                    out.write(json.dumps(sample, default=str) + '\n'); out.flush()
                    if reason: self.trip(reason)
                    self.done.wait(max(0, 2 - (time.monotonic() - began)))
        except Exception as error:
            # 不记录请求/凭据异常正文。
            self.trip('monitor_failure_' + type(error).__name__)

    def close(self):
        self.done.set()
        if self.thread: self.thread.join(8)
        if self.thread and self.thread.is_alive(): self.trip('monitor_did_not_finish')


def start_backend(runtime, directory, java, concurrency, pool, bootstrap=False, publisher_window=32):
    env, evidence = docker_guard(runtime)
    with db_connect(env) as db:
        if bootstrap:
            with db.cursor() as cur:
                cur.execute('SELECT COUNT(*) n FROM information_schema.tables WHERE table_schema=DATABASE()')
                if cur.fetchone()['n'] != 0: raise RuntimeError('首次Flyway启动必须是空隔离schema')
            if json.loads(redis('GET', 'ticketing:jmeter:environment-guard')) != hashlib.sha256(env['JMETER_GUARD_TOKEN'].encode()).hexdigest():
                raise RuntimeError('首次空库启动的独立Redis随机guard不符')
        else:
            db_guard(db, env)
    inherited = {key: value for key, value in os.environ.items()
                 if not key.upper().startswith(('MYSQL_', 'REDIS_', 'RABBITMQ_', 'SPRING_', 'TICKETING_', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS'))}
    inherited.update(env)
    inherited['TICKETING_DIAGNOSTICS_SHUTDOWN_TOKEN'] = secrets.token_hex(32)
    args = [java, '-Xms128m', '-Xmx512m', '-XX:+UseG1GC', f'-Xlog:gc:file={directory / "backend-gc.log"}',
            '-jar', str(ROOT / 'backend/target/ticketing-backend-0.1.0-SNAPSHOT.jar'),
            f'--ticketing.purchase.consumer.concurrency={concurrency}', '--ticketing.purchase.consumer.prefetch=1',
            f'--ticketing.purchase.in-flight-window={publisher_window}',
            f'--spring.datasource.hikari.maximum-pool-size={pool}', '--ticketing.diagnostics.enabled=true',
            '--management.server.address=127.0.0.1', '--management.server.port=18081',
            '--management.endpoints.web.exposure.include=health,purchaseDiagnostics,shutdown',
            '--management.endpoint.shutdown.enabled=true',
            '--spring.lifecycle.timeout-per-shutdown-phase=30s',
            '--spring.task.scheduling.shutdown.await-termination=true',
            '--spring.task.scheduling.shutdown.await-termination-period=30s',
            '--management.metrics.distribution.percentiles.hikaricp.connections.acquire=0.5,0.95,0.99']
    logfile = (directory / 'backend.log').open('w', encoding='utf-8')
    process = subprocess.Popen(args, cwd=ROOT, env=inherited, stdout=logfile, stderr=subprocess.STDOUT,
                               creationflags=subprocess.CREATE_NO_WINDOW)
    process.diagnostics_shutdown_token = inherited['TICKETING_DIAGNOSTICS_SHUTDOWN_TOKEN']
    try:
        for _ in range(90):
            if process.poll() is not None: raise RuntimeError('隔离后端启动失败；检查本轮私有日志')
            try:
                data = diag()
                if data['pid'] != process.pid or data['pool']['maximum'] != pool or data['consumer']['configured'] != concurrency or data['consumer']['prefetch'] != 1:
                    raise RuntimeError('隔离后端实际PID/池/消费者/预取与参数不符')
                if data['consumer']['active'] == concurrency:
                    with db_connect(env) as ready_db, ready_db.cursor() as cursor:
                        cursor.execute("SELECT COUNT(*) n FROM user_account WHERE username=%s AND role='ADMIN' AND status='ACTIVE'", (env['ADMIN_BOOTSTRAP_USERNAME'],))
                        if cursor.fetchone()['n'] != 1:
                            time.sleep(1)
                            continue
                    save(directory / 'runtime-proof.json', dict(command=args, pid=process.pid, docker=evidence, diagnostics=data))
                    denial = requests.post('http://127.0.0.1:18081/actuator/shutdown', timeout=2)
                    if denial.status_code != 403: raise RuntimeError('无令牌关闭请求未被拒绝')
                    return process, logfile
            except requests.RequestException:
                pass
            time.sleep(1)
        raise RuntimeError('等待隔离诊断后端超时')
    except Exception:
        stop_backend(process, logfile, directory)
        raise


def stop_backend(process, logfile, directory):
    """通过独立随机令牌请求Spring正常关闭，等待监听器/定时事务结束；失败则保留JVM。"""
    began = time.monotonic()
    try:
        if process.poll() is None:
            response = requests.post('http://127.0.0.1:18081/actuator/shutdown', timeout=4,
                headers={'X-Ticketing-Diagnostics-Stop': process.diagnostics_shutdown_token})
            response.raise_for_status()
            process.wait(timeout=60)
        save(directory/'shutdown.json', dict(pid=process.pid, exitCode=process.returncode,
            graceful=True, elapsedSeconds=time.monotonic()-began))
        logfile.close()
        return True
    except Exception as error:
        save(directory/'shutdown.json', dict(pid=process.pid, graceful=False, errorType=type(error).__name__))
        print(f'正常关闭未完成，保留本轮隔离后端PID {process.pid}，不强杀事务', file=sys.stderr)
        return False


def login(row):
    username, password = row['username'], row['password']
    with requests.Session() as session:
        base = 'http://127.0.0.1:18080/api/v1'
        first = session.get(base + '/auth/csrf', timeout=8); first.raise_for_status()
        csrf = first.json()['data']
        response = session.post(base + '/auth/login', data=dict(username=username, password=password),
                                headers={csrf['headerName']: csrf['token']}, timeout=10, allow_redirects=False)
        if response.status_code != 200: raise RuntimeError('预登录被安全规则拒绝')
        final = session.get(base + '/auth/csrf', timeout=8); final.raise_for_status()
        csrf = final.json()['data']
        return dict(cookie='; '.join(f'{c.name}={c.value}' for c in session.cookies),
                    csrfHeader=csrf['headerName'], csrfToken=csrf['token'])


def drain(env, seconds=120):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        mq = queue(env)
        pending = json.loads(redis('GET', 'ticketing:seckill:v2:ticketingjmeter:pending'))
        recovery = json.loads(redis('ZCARD', 'ticketing:seckill:v2:ticketingjmeter:recovery'))
        if not mq['ready'] and not mq['unacked'] and int(pending or 0) == 0 and recovery == 0:
            return dict(mq=mq, pending=int(pending or 0), recovery=recovery, complete=True)
        time.sleep(2)
    return dict(mq=mq, pending=pending, recovery=recovery, complete=False)


def audit(db, env, metadata, batch):
    prefix = f'jmeter-{batch}-%'
    with db.cursor() as cur:
        cur.execute("""SELECT p.id,p.idempotency_key,p.status,p.order_id,p.failure_code,
                       UNIX_TIMESTAMP(p.accepted_at)*1000 accepted_ms,UNIX_TIMESTAMP(o.created_at)*1000 created_ms
                       FROM purchase_request p LEFT JOIN ticket_order o ON o.id=p.order_id
                       WHERE p.idempotency_key LIKE %s AND p.idempotency_operation='CREATE_ORDER'""", (prefix,))
        orders = cur.fetchall()
        cur.execute("""SELECT COUNT(*) n FROM purchase_request p LEFT JOIN ticket_order o ON o.id=p.order_id
                       LEFT JOIN ticket_order_item i ON i.order_id=o.id
                       WHERE p.idempotency_key LIKE %s AND
                       ((p.status='SUCCEEDED' AND (o.id IS NULL OR p.user_id<>o.user_id OR p.sku_id<>i.sku_id
                       OR p.quantity<>i.quantity OR p.idempotency_key<>o.idempotency_key OR i.id IS NULL
                       OR o.total_amount_cents<>i.unit_price_cents*i.quantity OR o.idempotency_operation<>'CREATE_ORDER'))
                       OR (p.status<>'SUCCEEDED' AND p.order_id IS NOT NULL))""", (prefix,))
        mismatch = cur.fetchone()['n']
        cur.execute("""SELECT COUNT(*) n FROM purchase_request p JOIN ticket_order o ON o.id=p.order_id
                       WHERE p.idempotency_key LIKE %s AND (o.session_id<>%s OR
                       (SELECT COUNT(*) FROM ticket_order_item i WHERE i.order_id=o.id)<>1
                       OR o.request_hash<>p.request_hash)""", (prefix, metadata['sessionId']))
        mismatch += cur.fetchone()['n']
        cur.execute("SELECT * FROM ticket_sku WHERE id=%s", (metadata['skuId'],))
        sku = cur.fetchone()
        cur.execute("""SELECT COUNT(*) n FROM user_session_quota q WHERE q.session_id=%s AND
                       (q.occupied_quantity>4 OR q.occupied_quantity<>(SELECT COALESCE(SUM(i.quantity),0)
                       FROM ticket_order o JOIN ticket_order_item i ON i.order_id=o.id
                       WHERE o.user_id=q.user_id AND o.session_id=q.session_id AND o.status IN ('PENDING','PAID')))""",
                    (metadata['sessionId'],))
        quota_errors = cur.fetchone()['n']
        cur.execute("""SELECT COUNT(*) n FROM ticket_order o LEFT JOIN user_session_quota q
                       ON q.user_id=o.user_id AND q.session_id=o.session_id
                       WHERE o.session_id=%s AND o.status IN ('PENDING','PAID') AND q.user_id IS NULL""",
                    (metadata['sessionId'],))
        quota_errors += cur.fetchone()['n']
        cur.execute("""SELECT COALESCE(SUM(IF(o.status='PENDING',i.quantity,0)),0) locked,
                       COALESCE(SUM(IF(o.status='PAID',i.quantity,0)),0) sold
                       FROM ticket_order_item i JOIN ticket_order o ON o.id=i.order_id WHERE i.sku_id=%s""",
                    (metadata['skuId'],))
        conserved = cur.fetchone()
        cur.execute("""SELECT COUNT(*) n FROM (SELECT order_id FROM purchase_request WHERE idempotency_key LIKE %s
                       AND order_id IS NOT NULL GROUP BY order_id HAVING COUNT(*)>1) d""", (prefix,))
        duplicate = cur.fetchone()['n']
        cur.execute("""SELECT COUNT(*) n FROM ticket_order o WHERE o.idempotency_key LIKE %s AND NOT EXISTS
                       (SELECT 1 FROM purchase_request p WHERE p.order_id=o.id AND p.status='SUCCEEDED')""", (prefix,))
        orphan = cur.fetchone()['n']
    ledger = json.loads(redis('HGETALL', f"ticketing:seckill:v2:ticketingjmeter:sku:{metadata['skuId']}"))
    expected = sku['total_stock'] - sku['locked_stock'] - sku['sold_stock']
    response = requests.get('http://127.0.0.1:15674/api/queues/%2F/ticketing.purchase.orders.dead',
                            auth=(env['RABBITMQ_USER'], env['RABBITMQ_PASSWORD']), timeout=2)
    response.raise_for_status()
    dead = response.json().get('messages', 0)
    checks = dict(linkErrors=mismatch, quotaErrors=quota_errors, duplicateOrders=duplicate, orphanOrders=orphan,
                  mysql=dict(total=sku['total_stock'], locked=sku['locked_stock'], sold=sku['sold_stock'], available=expected),
                  redis=ledger, dead=dead, stockFromOrders=conserved)
    checks['valid'] = (not any((mismatch, quota_errors, duplicate, orphan, dead)) and expected >= 0
                       and sku['locked_stock'] >= 0 and sku['sold_stock'] >= 0
                       and conserved['locked'] == sku['locked_stock'] and conserved['sold'] == sku['sold_stock']
                       and int(ledger.get('available', -1)) == expected and int(ledger.get('pending', -1)) == 0)
    return orders, checks


def run_round(args):
    runtime = args.runtime.resolve()
    if not runtime.is_relative_to((ROOT / '.local/jmeter').resolve()): raise RuntimeError('运行目录须在.local/jmeter')
    env, evidence = docker_guard(runtime)
    native = WindowsResources(); baseline = native.host(); time.sleep(1); baseline = native.host()
    load_name = f'w{args.inflight}' if args.inflight else f'r{args.rps:g}'
    directory = runtime.parent / f'c{args.concurrency}-p{args.pool}-{load_name}-{secrets.token_hex(3)}'
    directory.mkdir()
    save(directory / 'preflight.json', dict(host=baseline, docker=evidence, source=command(['git', 'rev-parse', 'HEAD'])))
    if SafetyGate().update(baseline):
        raise RuntimeError('资源预检不通过；未启动后端或发压')
    metadata = json.loads((runtime / 'prepared-data.json').read_text(encoding='utf-8'))
    backend, logfile = start_backend(runtime, directory, args.java, args.concurrency, args.pool,
                                    publisher_window=args.publisher_window)
    monitor = Monitor(env, directory, backend.pid, metadata['skuId'])
    monitor.start()
    jmeter = None
    feed = None
    try:
        previous_drain = drain(env, 20)
        if not previous_drain['complete']: raise RuntimeError('上一批未排空，拒绝发单')
        with (runtime / 'measure-users.csv').open(encoding='utf-8-sig', newline='') as stream:
            accounts = list(csv.DictReader(stream))
        cursor_path = runtime / 'next-account.json'
        offset = json.loads(cursor_path.read_text()) if cursor_path.exists() else 0
        count = args.max_posts if args.inflight else int(args.rps * args.seconds)
        warmup_count = 10 if args.selftest else 20
        if count + warmup_count + offset > len(accounts): raise RuntimeError('独立账号余额不足，不复用已有账号')
        selected = accounts[offset:offset + count + warmup_count]
        save(cursor_path, offset + count + warmup_count)  # 失败账号也不再用于其他档位。
        sessions = []
        with concurrent.futures.ThreadPoolExecutor(max_workers=args.login_workers) as executor:
            for value in executor.map(login, selected):
                sessions.append(value)
                if monitor.stop.exists(): raise RuntimeError('预登录期间资源/监测保护触发；未发购票')
        if monitor.stop.exists(): raise RuntimeError('资源保护触发')
        warmup_batch = 'warmup-' + secrets.token_hex(8)
        for index, session in enumerate(sessions[:warmup_count]):
            if monitor.stop.exists(): raise RuntimeError('预热期间资源保护触发')
            response = requests.post('http://127.0.0.1:18080/api/v1/orders',
                    headers={'Cookie': session['cookie'], session['csrfHeader']: session['csrfToken'],
                             'Idempotency-Key': f'jmeter-{warmup_batch}-{index}'},
                    json=dict(skuId=metadata['skuId'], quantity=1), timeout=15)
            if response.status_code != 202: raise RuntimeError('独立预热被拒绝；本轮不进入正式负载')
            time.sleep(.2)
        if not drain(env, 40)['complete']: raise RuntimeError('独立预热未排空')
        sessions = sessions[warmup_count:]
        batch = 'diag-' + secrets.token_hex(8)
        start = int(time.time() * 1000) + 12000
        end = start + args.seconds * 1000
        monitor.measurement_start_ms = start
        sessions_path = directory / 'sessions.json'
        save(sessions_path, dict(target='http://127.0.0.1:18080/api/v1/orders', batch=batch,
                                 skuId=metadata['skuId'], rps=args.rps, inflight=args.inflight,
                                 flowFile=str(directory/'flow.json'), startMs=start, endMs=end, sessions=sessions))
        with db_connect(env) as db:
            db_guard(db, env)
            cur = db.cursor()
            clocks = []
            for _ in range(3):
                before = time.time() * 1000
                cur.execute('SELECT UNIX_TIMESTAMP(UTC_TIMESTAMP(6))*1000 now_ms')
                mysql_ms = float(cur.fetchone()['now_ms']); after = time.time() * 1000
                clocks.append(dict(offsetMs=mysql_ms - (before + after)/2, rttMs=after-before))
            before_digest = digest(db)
            cur.execute('SELECT @@max_connections max_connections,@@sql_mode sql_mode,VERSION() version')
            mysql = cur.fetchone()
        before_metrics = diag()
        if args.inflight:
            feed = CompletionFeed(directory, monitor, before_metrics['completions']['count'])
            feed.start()
        jmeter_args = [args.java, '-Xms64m', '-Xmx256m', '-XX:+UseG1GC',
                       f'-Xlog:gc:file={directory / "jmeter-gc.log"}', '-jar', args.jmeter_jar,
                       '-n', '-t', str(PLAN), f'-JsessionsFile={sessions_path}', f'-JstopFile={monitor.stop}',
                       f'-Jthreads={args.threads}', '-Jsample_variables=batch,idempotencyKey,requestId,failureCode,scheduledMs,postStartMs,postElapsedMs,requestIndex,completionCredit,orderCompletedMs,orderObservedMs,orderStatus,flowReadRetries',
                       '-Jjmeter.save.saveservice.output_format=csv', '-Jjmeter.save.saveservice.print_field_names=true',
                       '-Jjmeter.save.saveservice.autoflush=true', '-Jjmeter.save.saveservice.response_data=false',
                       '-Jjmeter.save.saveservice.samplerData=false', '-Jjmeter.save.saveservice.responseHeaders=false',
                       '-Jjmeter.save.saveservice.requestHeaders=false', '-l', str(directory/'post.jtl'),
                       '-j', str(directory/'jmeter.log')]
        if args.selftest: monitor.fuse_after = 5
        stdout = (directory / 'jmeter-cli.log').open('w', encoding='utf-8')
        jmeter = subprocess.Popen(jmeter_args, cwd=ROOT, stdout=stdout, stderr=subprocess.STDOUT,
                                  creationflags=subprocess.CREATE_NO_WINDOW)
        monitor.jmeter_process = jmeter
        monitor.jmeter_pid = jmeter.pid
        save(directory/'run.json', dict(batch=batch, concurrency=args.concurrency, pool=args.pool,
                                      publisherWindow=args.publisher_window,
                                      offeredRps=None if args.inflight else args.rps, inflight=args.inflight,
                                      loadModel='completion-closed-loop' if args.inflight else 'paced-arrivals', skuId=metadata['skuId'],
                                      threads=args.threads, startMs=start, endMs=end, clocks=clocks, mysql=mysql,
                                      planSha256=hashlib.sha256(PLAN.read_bytes()).hexdigest(),
                                      artifactSha256=hashlib.sha256((ROOT/'backend/target/ticketing-backend-0.1.0-SNAPSHOT.jar').read_bytes()).hexdigest()))
        snapshots = set()
        while jmeter.poll() is None:
            elapsed = (time.time() * 1000 - start) / 1000
            for at in (10, 22):
                if elapsed >= at and at not in snapshots and elapsed < args.seconds:
                    snapshots.add(at)
                    dump = command([str(Path(args.java).with_name('jcmd.exe')), str(backend.pid), 'Thread.print', '-l'], timeout=4)
                    (directory / f'threads-{at}.txt').write_text(dump, encoding='utf-8')
            if time.time() * 1000 > end + 25000: monitor.trip('jmeter_did_not_finish_after_deadline')
            if time.time() * 1000 > end + 45000:
                raise RuntimeError('发压端不退出，保留现场，不结束后端在途事务')
            time.sleep(.5)
        monitor.jmeter_pid = None
        stdout.close()
        drained = drain(env)
        if feed: feed.close()
        after_metrics = diag()
        with db_connect(env) as db:
            db_guard(db, env)
            orders, checks = audit(db, env, metadata, batch)
            after_digest = digest(db)
        with (directory/'post.jtl').open(encoding='utf-8-sig', newline='') as stream:
            posts = list(csv.DictReader(stream))
        result = summarize(posts, orders, start, end)
        result.update(batch=batch, offeredRps=None if args.inflight else args.rps, inflight=args.inflight,
                      loadModel='completion-closed-loop' if args.inflight else 'paced-arrivals',
                      concurrency=args.concurrency, pool=args.pool, publisherWindow=args.publisher_window,
                      checks=checks, drained=drained, fuseReason=monitor.failure,
                      noEscalation=monitor.gate.no_escalation, jmeterExit=jmeter.returncode)
        stop_time = json.loads(monitor.stop.read_text())['timeMs'] if monitor.stop.exists() else None
        if monitor.stop.exists() and not monitor.failure:
            result['fuseReason'] = json.loads(monitor.stop.read_text())['reason']
        result['postsAfterStop'] = sum(int(p['postStartMs']) > stop_time for p in posts) if stop_time else 0
        result['postsAfterDeadline'] = sum(int(p['postStartMs']) >= end for p in posts)
        result['selftestPassed'] = bool(args.selftest and monitor.failure == 'selftest_stop_after_five_posts'
                                       and 5 <= len(posts) <= 10 and not result['postsAfterStop']
                                       and checks['valid'] and drained['complete'] and result['completeAccepted'])
        result['accountBudgetExhausted'] = bool(args.inflight and len(posts) >= count)
        result['completedWorkerCalls'] = completed_workers(after_metrics)-completed_workers(before_metrics)
        if args.inflight:
            # 自检与每个正式档都验证实际JTL的发放额度，防止闭环退化成只等HTTP 202。
            result['maxGrantedOutstanding'] = max((int(p['requestIndex'])+1-int(p['completionCredit']) for p in posts), default=0)
            observations = [json.loads(line) for line in (directory/'completion-feed.jsonl').read_text().splitlines()]
            result['completionObserverMs'] = quantiles(o['observerMs'] for o in observations)
            result['completionFeedMaxGapMs'] = max((b['timeMs']-a['timeMs'] for a,b in zip(observations,observations[1:])), default=0)
            finished = [p for p in posts if p.get('orderStatus') == 'SUCCEEDED']
            result['fullChainSuccessfulSamples'] = len(finished)
            result['postToSettledMs'] = quantiles(int(p['orderCompletedMs'])-int(p['postStartMs']) for p in finished)
            result['postToClientObservedMs'] = quantiles(int(p['orderObservedMs'])-int(p['postStartMs']) for p in finished)
            result['flowReadRetries'] = max((int(p.get('flowReadRetries') or 0) for p in posts), default=0)
        result['valid'] = bool(checks['valid'] and drained['complete'] and not monitor.stop.exists()
                               and not result['duplicatePostKeys'] and result['completeAccepted']
                               and not result['acceptedMissingRequestId'] and jmeter.returncode == 0
                               and result['accepted'] == result['attempted']
                               and (not args.inflight and result['attempted'] == count
                                    or args.inflight and result['attempted'] > 0 and not result['accountBudgetExhausted']
                                    and result['completedWorkerCalls'] == result['accepted'])
                               and (not args.inflight or result['maxGrantedOutstanding'] <= args.inflight
                                    and result['completionFeedMaxGapMs'] < 2000
                                    and result['fullChainSuccessfulSamples'] == result['accepted'])
                               and not result['postsAfterDeadline'])
        save(directory/'summary.json', result)
        save(directory/'orders.json', orders)
        save(directory/'metrics.json', dict(before=before_metrics, after=after_metrics))
        save(directory/'digests.json', dict(before=before_digest, after=after_digest))
        print(json.dumps({k: result[k] for k in ('batch','attempted','accepted','orderTps','httpMs','serverQueueAndCreateMs','valid','fuseReason','postsAfterStop')}, ensure_ascii=False))
        print('本轮结果 ' + str(directory))
        return result
    finally:
        # 任何采样/线程快照/审计异常都先关发单，不能在JMeter仍运行时结束后端。
        if jmeter is not None and jmeter.poll() is None:
            monitor.trip('runner_aborted_stop_new_posts')
            try:
                jmeter.wait(timeout=20)
            except subprocess.TimeoutExpired:
                print('发压进程尚未退出，保留后端及停止文件供核对', file=sys.stderr)
        if feed: feed.close()
        monitor.close()
        # 只结束本函数启动且已确认排空的隔离JVM；未排空时保留后端继续恢复。
        if (jmeter is None or jmeter.poll() is not None) and drain(env, 10)['complete']:
            if not stop_backend(backend, logfile, directory): raise RuntimeError('本轮正常关闭未完成，现场已保留')
        else:
            print(f'未排空，保留本轮隔离后端PID {backend.pid}；不终止事务', file=sys.stderr)


def initialize(args):
    runtime = args.runtime.resolve()
    if not runtime.is_relative_to((ROOT / '.local/jmeter').resolve()): raise RuntimeError('初始化须在.local/jmeter')
    if args.previous_runtime:
        rotate_previous(args)
    elif command(['docker', 'ps', '-a', '-q', '--filter', 'label=com.docker.compose.project=ticketing-jmeter']):
        raise RuntimeError('已有同名隔离容器，不覆盖或重建')
    if not args.previous_runtime and command(['docker', 'volume', 'ls', '-q', '--filter', 'label=com.docker.compose.project=ticketing-jmeter']):
        raise RuntimeError('已有同名隔离卷，不复用或清空')
    native = WindowsResources(); native.host(); time.sleep(2)
    host = native.host()
    runtime.parent.mkdir(parents=True, exist_ok=True)
    save(runtime.parent/'initial-host.json', host)
    if SafetyGate().update(host): raise RuntimeError('主机资源不满足安全底线')
    command(['powershell', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
             ROOT/'scripts/jmeter/prepare-environment.ps1', '-RuntimeDirectory', runtime])
    compose = ['docker', 'compose', '--project-name', 'ticketing-jmeter', '--env-file', runtime/'compose.env',
               '--file', ROOT/'deploy/compose.yml']
    if args.previous_runtime:
        suffix = secrets.token_hex(6)
        override = runtime/'volumes.override.json'
        save(override, {'volumes': {f'{s}_data': {'name': f'ticketing-jmeter_{s}_capacity_{suffix}'}
                                    for s in ('mysql', 'redis', 'rabbitmq')}})
        compose += ['--file', override]
    command(compose + ['up', '--pull', 'never', '-d', '--wait'], timeout=150)
    env, proof = docker_guard(runtime)
    with db_connect(env) as db, db.cursor() as cur:
        # 初次迁移前保持schema为空，随机标记先绑定同项目Redis，建数前再落DB guard。
        cur.execute('SELECT COUNT(*) n FROM information_schema.tables WHERE table_schema=DATABASE()')
        if cur.fetchone()['n'] != 0: raise RuntimeError('新隔离库非空，拒绝初始化')
    expected_guard = hashlib.sha256(env['JMETER_GUARD_TOKEN'].encode()).hexdigest()
    if json.loads(redis('SET', 'ticketing:jmeter:environment-guard', expected_guard, 'NX')) != 'OK':
        raise RuntimeError('初次Redis guard已存在；拒绝覆盖')
    directory = runtime.parent/'bootstrap'; directory.mkdir()
    backend, logfile = start_backend(runtime, directory, args.java, 2, 10, bootstrap=True)
    try:
        resource = native.host()
        save(directory/'host.json', resource)
        if SafetyGate().update(resource): raise RuntimeError('中间件及后端启动后资源不足，禁止建数/发压')
        text = command([sys.executable, '-B', ROOT/'scripts/jmeter/prepare-data.py',
                        '--env-file', runtime/'compose.env', '--users', '10000'], timeout=40)
        (directory/'prepare-output.txt').write_text(text, encoding='utf-8')
        save(directory/'isolation.json', dict(docker=proof, host=resource,
                                             redisAof=json.loads(redis('CONFIG', 'GET', 'appendonly')),
                                             redisFsync=json.loads(redis('CONFIG', 'GET', 'appendfsync'))))
        print('隔离环境与10000个独立账号已准备；凭据只写入忽略目录')
    finally:
        # 此时没有任何POST、订单或在途购票，安全结束自建的引导JVM。
        if not stop_backend(backend, logfile, directory): raise RuntimeError('引导后端正常关闭未完成，现场已保留')


def rotate_previous(args):
    """核验旧guard且排空后仅移除三个隔离容器，保留旧卷/凭据/结果，以新命名卷建批。"""
    previous = args.previous_runtime.resolve()
    if (not previous.is_relative_to((ROOT/'.local/jmeter').resolve()) or previous == args.runtime
            or args.runtime.exists()):
        raise RuntimeError('旧运行目录或新批次目录不合法；拒绝覆盖')
    for port in (18080, 18081):
        with socket.socket() as probe:
            if probe.connect_ex(('127.0.0.1', port)) == 0:
                raise RuntimeError('旧压测应用仍运行，拒绝轮换中间件')
    env, proof = docker_guard(previous)
    ids = command(['docker', 'ps', '-a', '--format', '{{.Names}}', '--filter',
                   'label=com.docker.compose.project=ticketing-jmeter']).splitlines()
    expected = {f'ticketing-jmeter-{s}-1' for s in ('mysql', 'redis', 'rabbitmq')}
    if set(ids) != expected: raise RuntimeError('隔离项目存在未知容器，拒绝轮换')
    if not drain(env, 10)['complete']: raise RuntimeError('旧批次购票未排空，拒绝轮换')
    with db_connect(env) as db, db.cursor() as cur:
        db_guard(db, env)
        cur.execute('SELECT COUNT(*) n FROM information_schema.innodb_trx WHERE trx_mysql_thread_id<>CONNECTION_ID()')
        if cur.fetchone()['n']: raise RuntimeError('仍有其他事务，拒绝轮换')
    volumes = []
    for item in proof:
        volume = json.loads(command(['docker', 'volume', 'inspect', item['volume']]))[0]
        if volume.get('Labels', {}).get('com.docker.compose.project') != 'ticketing-jmeter':
            raise RuntimeError('旧卷项目标签不符，拒绝轮换')
        volumes.append(item['volume'])
    args.runtime.parent.mkdir(parents=True, exist_ok=True)
    save(args.runtime.parent/'retained-previous-batch.json', dict(previousRuntime=str(previous),
          docker=proof, retainedVolumes=volumes, guardVerified=True, pendingDrained=True))
    command(['docker', 'stop', *sorted(expected)], timeout=60)
    command(['docker', 'rm', *sorted(expected)])
    print('旧隔离批次guard及排空已核验；仅移除其三个容器，旧数据卷全部保留')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--java', required=True)
    parser.add_argument('--jmeter-jar', required=True)
    parser.add_argument('--concurrency', type=int, choices=(2,4,8), default=2)
    parser.add_argument('--pool', type=int, choices=(10,16,20), default=10)
    parser.add_argument('--publisher-window', type=int, choices=(16,32,64), default=32,
                        help='隔离应用中同时等待购票消息确认的槽位数')
    parser.add_argument('--rps', type=float, default=10)
    parser.add_argument('--inflight', type=int, choices=(2,16,32,64,96,100), default=0,
                        help='按消费者完成数补发的闭环在途窗口，启用后不限制RPS')
    parser.add_argument('--max-posts', type=int, default=2400, help='闭环独立账号预算，耗尽则本轮不能认定容量')
    parser.add_argument('--threads', type=int, default=12, help='仅HTTP发送线程，不等于在途申请或消费者数量')
    parser.add_argument('--login-workers', type=int, choices=(1,2,4), default=4, help='测量窗口外的预登录并行度')
    parser.add_argument('--seconds', type=int, default=30)
    parser.add_argument('--selftest', action='store_true')
    parser.add_argument('--host-only', action='store_true')
    parser.add_argument('--initialize', action='store_true')
    parser.add_argument('--previous-runtime', type=Path, help='初始化新批次前核验并轮换旧隔离容器，保留全部旧卷')
    parser.add_argument('--fresh-sku', action='store_true', help='只在已有guard隔离库添加正常SINGLE测试票档，不清理旧数据')
    args = parser.parse_args()
    args.runtime = args.runtime.resolve()
    if not args.runtime.is_relative_to((ROOT / '.local/jmeter').resolve()): parser.error('运行目录必须在.local/jmeter')
    if args.rps <= 0 or args.seconds < (10 if args.selftest else 30): parser.error('正式窗口至少30秒；熔断自检至少10秒')
    if args.inflight and not 10 <= args.max_posts <= 10000: parser.error('闭环独立账号预算须在10至10000')
    if not 1 <= args.threads <= 500: parser.error('发压线程必须在1至500')
    if args.previous_runtime and not args.initialize: parser.error('旧批次轮换只可与initialize一起使用')
    if args.host_only:
        native = WindowsResources(); samples = []
        for _ in range(3):
            samples.append(native.host()); time.sleep(2)
        save(args.runtime.parent/'host-preflight.json', samples)
        print(json.dumps(samples, ensure_ascii=False)); return
    if args.initialize:
        initialize(args)
        return
    if args.fresh_sku:
        env, _ = docker_guard(args.runtime)
        if not drain(env, 10)['complete']: raise RuntimeError('尚有在途购票，不建立新测试票档')
        path = args.runtime / 'prepared-data.json'
        metadata = json.loads(path.read_text(encoding='utf-8'))
        with db_connect(env) as db, db.cursor() as cur:
            db_guard(db, env)
            cur.execute("""INSERT INTO ticket_sku(session_id,name,price_cents,total_stock,locked_stock,sold_stock,
                           sale_starts_at,sale_ends_at,max_quantity_per_order,status)
                           SELECT session_id,'隔离诊断新票档',price_cents,10000,0,0,
                           sale_starts_at,sale_ends_at,max_quantity_per_order,status FROM ticket_sku
                           WHERE id=%s AND inventory_strategy='SINGLE' AND status='ON_SALE'""", (metadata['skuId'],))
            if cur.rowcount != 1: raise RuntimeError('原正常SINGLE测试票档不存在')
            new_id = cur.lastrowid
        save(args.runtime / f'prepared-data-sku-{metadata["skuId"]}.json', metadata)
        metadata['skuId'] = str(new_id)
        metadata['previousSkuRetained'] = True
        save(path, metadata)
        print('新增正常SINGLE诊断票档 ' + str(new_id) + '；原数据全部保留')
        return
    run_round(args)


if __name__ == '__main__':
    main()
