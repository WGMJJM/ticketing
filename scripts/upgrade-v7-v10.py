"""已核验备份的原开发库受控升级。默认只校验参数；--apply还须明确停写及备份摘要。"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import sys
import time
from datetime import datetime, timezone
import pymysql
import requests

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('v7_helpers', ROOT / 'scripts/recover-v7.py')
helpers = importlib.util.module_from_spec(spec)
spec.loader.exec_module(helpers)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', required=True)
    parser.add_argument('--verified-report', required=True)
    parser.add_argument('--backup', required=True)
    parser.add_argument('--backup-sha256', required=True)
    parser.add_argument('--confirm-database')
    parser.add_argument('--maintenance-confirmed', action='store_true')
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('--label', required=True)
    parser.add_argument('--resume-preflight-failure', help='只允许先前原库未变化、仍完整V7的启动预检失败；不处理任何失败DDL')
    parser.add_argument('--resume-migrated-startup', help='仅继续已成功V10且无失败历史的配置/启动验收，不修复DDL')
    args = parser.parse_args()
    if not re.fullmatch(r'[a-zA-Z0-9_-]{1,40}', args.label):
        raise RuntimeError('标签无效')
    output = ROOT / 'docs/architecture/results' / (args.label + '-development-upgrade.json')
    if output.exists():
        raise RuntimeError('升级证据已存在，禁止覆盖')
    backup = (ROOT / args.backup).resolve()
    backup.relative_to((ROOT / '.local').resolve())
    digest = hashlib.sha256(backup.read_bytes()).hexdigest()
    verification = json.loads((ROOT / args.verified_report).read_text(encoding='utf-8'))
    source = verification['checks'][1]
    if (not verification['passed'] or digest != args.backup_sha256 or digest != source['backupSha256']
            or not any(c['name'] == 'V7备份两次真实恢复逐表摘要完全一致' and c['passed'] for c in verification['checks'])):
        raise RuntimeError('必须使用本轮已真实恢复验证且摘要匹配的备份')
    if not args.apply:
        print('参数及已验证备份匹配；未连接、启动或升级原库。实际执行须明确--apply及停写批准。')
        return
    if args.confirm_database != 'ticketing' or not args.maintenance_confirmed:
        raise RuntimeError('缺少具体原库及本次停写窗口的明确确认')
    if (ROOT / '.local/performance/app.pid').exists():
        raise RuntimeError('先停止专用后端，保持唯一Spring Boot进程')
    with socket.socket() as probe:
        if probe.connect_ex(('127.0.0.1', 8080)) == 0:
            raise RuntimeError('8080已有服务，不能推断原库已停写')
    endpoint = subprocess.check_output(['docker', 'context', 'inspect', '--format', '{{.Endpoints.docker.Host}}'], text=True).strip()
    if not endpoint.startswith(('npipe://', 'unix://')) or os.environ.get('DOCKER_HOST', '').startswith(('tcp:', 'ssh:')):
        raise RuntimeError('仅允许本机已核对的Docker实例')
    container = json.loads(subprocess.check_output(['docker', 'inspect', 'ticketing-mysql-1']))[0]
    if container['Config']['Labels'].get('com.docker.compose.project') != 'ticketing':
        raise RuntimeError('原库身份或已批准的停止状态变化，停止升级')
    if args.resume_preflight_failure and args.resume_migrated_startup:
        raise RuntimeError('续行模式不能同时使用')
    if container['State']['Running']:
        resume_report = args.resume_migrated_startup or args.resume_preflight_failure
        if not resume_report:
            raise RuntimeError('原库运行状态变化；只允许完整V7预检失败的显式续行')
        previous_report = json.loads((ROOT / resume_report).read_text(encoding='utf-8'))
        if (previous_report.get('stage') != 'STOP_PRESERVE_SCENE' or previous_report.get('passed')
                or previous_report.get('backupSha256') != digest or previous_report.get('before') != source['tableFacts']):
            raise RuntimeError('不是同一备份且未变更的V7预检失败')
    settings = {}
    for line in (ROOT / '.env').read_text(encoding='utf-8-sig').splitlines():
        if not line.strip() or line.lstrip().startswith('#'):
            continue
        match = re.fullmatch(r'([A-Za-z_][A-Za-z0-9_]*)=(.*)', line.strip())
        if not match:
            raise RuntimeError('环境文件只支持KEY=value')
        value = match[2].strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
        settings[match[1]] = value
    if (settings.get('MYSQL_HOST'), settings.get('MYSQL_PORT'), settings.get('MYSQL_DATABASE')) != ('127.0.0.1', '3307', 'ticketing'):
        raise RuntimeError('原库目标与授权不一致')
    if any(key.startswith(('SPRING_', 'TICKETING_', 'MANAGEMENT_')) or key in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS') for key in settings):
        raise RuntimeError('受控升级不接受可重定向Spring配置或注入JVM的额外环境项')
    private = ROOT / '.local/stage19' / args.label
    private.mkdir(parents=True, exist_ok=False)
    report = {'passed': False, 'startedAt': datetime.now(timezone.utc).isoformat(),
              'target': '127.0.0.1:3307/ticketing', 'backupSha256': digest,
              'jarSha256': hashlib.sha256((ROOT / args.jar).read_bytes()).hexdigest(),
              'authorization': '用户明确批准本次停写窗口及具体备份的V7到V10升级',
              'repairOrCleanExecuted': False, 'businessWritesEnabled': False}
    def save():
        output.write_text(json.dumps(report, ensure_ascii=False, indent=2, default=str) + '\n', encoding='utf-8')
    def connect():
        return pymysql.connect(host='127.0.0.1', port=3307, user=settings['MYSQL_USER'], password=settings['MYSQL_PASSWORD'],
            database='ticketing', autocommit=True, cursorclass=pymysql.cursors.DictCursor)
    def original_facts(db, columns):
        preserved = {}
        with db.cursor() as cur:
            for table, metadata in columns.items():
                if table == 'flyway_schema_history':
                    continue
                names = ','.join('`' + col['Field'] + '`' for col in metadata)
                primary = ','.join('`' + col['Field'] + '`' for col in metadata if col['Key'] == 'PRI')
                cur.execute('SELECT ' + names + ' FROM `' + table + '` ORDER BY ' + primary)
                rows = cur.fetchall()
                digest_rows = hashlib.sha256()
                for row in rows:
                    encoded = json.dumps(row, default=lambda v: v.hex() if isinstance(v, bytes) else str(v),
                        sort_keys=True, ensure_ascii=False, separators=(',', ':')).encode()
                    digest_rows.update(len(encoded).to_bytes(8, 'big')); digest_rows.update(encoded)
                preserved[table] = {'rows': len(rows), 'sha256': digest_rows.hexdigest()}
        return preserved
    compose = ['docker', 'compose', '--project-name', 'ticketing', '--env-file', str(ROOT / '.env'), '-f', str(ROOT / 'deploy/compose.yml')]
    # 配置不回显凭据；复用现有镜像和数据卷。
    subprocess.run(compose + ['config', '--quiet'], check=True, capture_output=True)
    with (private / 'compose.log').open('wb') as log:
        subprocess.run(compose + ['up', '-d', '--pull', 'never', '--wait'], check=True, stdout=log, stderr=log)
    with connect() as db:
        current_facts = helpers.facts(db)
        before = source['tableFacts']
        if not args.resume_migrated_startup and current_facts != before:
            raise RuntimeError('备份后原库发生变化，必须重新备份与恢复验证')
        with db.cursor() as cur:
            cur.execute('SELECT @@server_uuid id')
            report['serverUuid'] = cur.fetchone()['id']
            cur.execute('SELECT version,success FROM flyway_schema_history ORDER BY installed_rank')
            history = cur.fetchall()
            latest = 10 if args.resume_migrated_startup else 7
            if history != [{'version': str(i), 'success': 1} for i in range(1, latest + 1)]:
                raise RuntimeError('原库版本已变化，停止')
            columns = {}
            if not args.resume_migrated_startup:
                for table in before:
                    cur.execute('SHOW COLUMNS FROM `' + table + '`')
                    columns[table] = cur.fetchall()
        if args.resume_migrated_startup:
            sys.path.insert(0, str(ROOT / 'scripts/performance'))
            import perf
            reference = verification['schemas']['restoredBackup']
            if not re.fullmatch(r'ticketing_s19_[a-f0-9]{16}_it', reference):
                raise RuntimeError('V7恢复参考库格式不符')
            with perf.connect() as reference_db, reference_db.cursor() as reference_cur:
                reference_cur.execute('USE `' + reference + '`')
                if helpers.facts(reference_db) != before:
                    raise RuntimeError('V7恢复参考事实发生变化')
                for table in before:
                    reference_cur.execute('SHOW COLUMNS FROM `' + table + '`')
                    columns[table] = reference_cur.fetchall()
            preserved = original_facts(db, columns)
            if any(preserved[table] != value for table, value in before.items() if table != 'flyway_schema_history'):
                raise RuntimeError('V10原业务列与已验证V7备份不符，停止启动')
        report.update(before=before, sourceStillMatchesVerifiedBackup=True, historyAtResume=history,
                      migrationPreviouslyApplied=bool(args.resume_migrated_startup), stage='validated-before-ddl')
        save()
    prefixes = ('SPRING_', 'MANAGEMENT_', 'TICKETING_', 'MYSQL_', 'RABBITMQ_', 'REDIS_', 'SERVER_',
                'ORDER_', 'WAITLIST_', 'PURCHASE_', 'OUTBOX_', 'CATALOG_', 'ADMIN_', 'SESSION_', 'TICKET_', 'CHECKIN_', 'RATE_LIMIT_', 'PERF_', 'CLIENT_ADDRESS_')
    env = {k: v for k, v in os.environ.items() if not k.upper().replace('.', '_').replace('-', '_').startswith(prefixes)
           and k.upper() not in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS')}
    env.update(settings)
    # 显式Flyway URL创建独立连接，必须同时提供迁移凭据，不能退回OS用户名。
    env.update(SPRING_FLYWAY_USER=settings['MYSQL_USER'], SPRING_FLYWAY_PASSWORD=settings['MYSQL_PASSWORD'])
    env.update(ORDER_QUOTA_MIGRATION_APPROVED='true', ADMIN_BOOTSTRAP_ENABLED='false',
        ORDER_EXPIRY_ENABLED='false', ORDER_AFTERCARE_ENABLED='false', PURCHASE_JOBS_ENABLED='false',
        PURCHASE_MESSAGING_ENABLED='false', OUTBOX_JOBS_ENABLED='false', WAITLIST_JOBS_ENABLED='false',
        WAITLIST_MESSAGING_ENABLED='false', TICKET_BACKFILL_ENABLED='false', SERVER_PORT='8080')
    jdbc_url = 'jdbc:mysql://127.0.0.1:3307/ticketing?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&characterEncoding=UTF-8'
    arguments = ['java', '-Xms256m', '-Xmx512m', '-jar', str((ROOT / args.jar).resolve()),
                 '--spring.datasource.url=' + jdbc_url, '--spring.flyway.url=' + jdbc_url,
                 '--spring.flyway.locations=classpath:db/migration', '--spring.flyway.clean-disabled=true']
    def start_and_verify(approved):
        env['ORDER_QUOTA_MIGRATION_APPROVED'] = str(approved).lower()
        with (private / 'application.log').open('ab') as log:
            process = subprocess.Popen(arguments, cwd=ROOT, env=env, stdout=log, stderr=log,
                creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
        try:
            for _ in range(90):
                if process.poll() is not None:
                    raise RuntimeError('原库升级或启动失败，保持停写并保留现场及备份，禁止自动repair')
                try:
                    response = requests.get('http://127.0.0.1:8080/actuator/health', timeout=1)
                    if response.status_code == 200 and response.json()['status'] == 'UP':
                        if not approved:
                            with (private / 'http-smoke.log').open('wb') as smoke_log:
                                subprocess.run([sys.executable, str(ROOT / 'scripts/check-deployment.py'),
                                    '--base-url', 'http://127.0.0.1:8080', '--output',
                                    str(ROOT / 'docs/architecture/results' / (args.label + '-http-smoke.json'))],
                                    check=True, stdout=smoke_log, stderr=smoke_log)
                        return
                except requests.RequestException:
                    pass
                time.sleep(1)
            raise RuntimeError('原库应用启动超时，保持停写')
        finally:
            process.terminate()
            process.wait(timeout=20)
    try:
        report['stage'] = 'migrating'
        save()
        start_and_verify(not bool(args.resume_migrated_startup))
        with connect() as db, db.cursor() as cur:
            preserved = original_facts(db, columns)
            if any(preserved[table] != value for table, value in before.items() if table != 'flyway_schema_history'):
                raise RuntimeError('原业务字段对账异常，继续停写')
            checks = []
            cur.execute('SET TRANSACTION ISOLATION LEVEL REPEATABLE READ')
            cur.execute('START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY')
            for script in ['audit.sql', 'audit-purchase.sql', 'audit-quota.sql', 'audit-buckets.sql']:
                sql = '\n'.join(line for line in (ROOT / 'scripts/performance' / script).read_text(encoding='utf-8').splitlines()
                                if not line.lstrip().startswith('--')).replace('FROM ticket_sku', 'FROM ticket_stock_balance')
                for statement in sql.split(';'):
                    if statement.strip():
                        cur.execute(statement); checks.extend(cur.fetchall())
            db.commit()
            if any(row['violation_count'] for row in checks):
                raise RuntimeError('原库SQL审计异常，继续停写')
            cur.execute('SELECT version,success FROM flyway_schema_history ORDER BY installed_rank')
            report['historyAfter'] = cur.fetchall()
            report.update(preservedOriginalColumns=preserved, audit=checks, allBusinessFactsPreserved=True)
        # 完全撤销一次性批准后再次启动、Flyway验证及三个中间件健康检查。
        if not args.resume_migrated_startup:
            start_and_verify(False)
        report.update(passed=True, stage='verified-stopped', oneTimeApprovalActive=False,
                      restartWithoutApprovalPassed=True, endedAt=datetime.now(timezone.utc).isoformat(),
                      finalApplicationRunning=False, productionDeploymentAuthorized=False)
        save()
        print('原开发库V7到V10升级及原字段对账、全部SQL审计、撤销批准后启动验证通过；应用已停止。')
    except BaseException:
        report.update(stage='STOP_PRESERVE_SCENE', oneTimeApprovalActive=False)
        save()
        raise


if __name__ == '__main__':
    main()
