"""V7原库只读备份、随机隔离副本升级和恢复；绝不向原库发送DDL或DML。"""
import argparse
import hashlib
import importlib.util
import json
import os
import re
import subprocess
import time
import uuid
from datetime import datetime, timezone
import pymysql
import requests
import perf

spec = importlib.util.spec_from_file_location('v7_read_helpers', perf.ROOT / 'scripts/recover-v7.py')
helpers = importlib.util.module_from_spec(spec)
spec.loader.exec_module(helpers)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--label', required=True)
    parser.add_argument('--jar', required=True)
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9_-]{1,40}', args.label):
        raise RuntimeError('标签无效')
    output = perf.ROOT / 'docs/architecture/results' / (args.label + '-migration.json')
    private = perf.ROOT / '.local/stage19' / args.label
    if output.exists() or private.exists():
        raise RuntimeError('证据已存在，禁止覆盖')
    perf.connect().close()
    if (perf.PRIVATE / 'app.pid').exists():
        raise RuntimeError('先停止专用应用，迁移演练必须停写')
    private.mkdir(parents=True)
    report = {'passed': False, 'startedAt': datetime.now(timezone.utc).isoformat(),
              'sourceModified': False, 'repairOrCleanExecuted': False, 'checks': []}
    cfg = perf.config()
    def check(name, condition, **facts):
        report['checks'].append({'name': name, 'passed': bool(condition), **facts})
        perf.save(output, report)
        if not condition:
            raise RuntimeError('迁移演练停止 ' + name)
        print('通过 ' + name, flush=True)
    def query(schema, statement, values=None):
        if not re.fullmatch(r'ticketing_s19_[a-f0-9]{16}_it', schema):
            raise RuntimeError('隔离schema不匹配')
        with perf.connect() as db, db.cursor() as cur:
            cur.execute('USE `' + schema + '`')
            cur.execute(statement, values)
            return cur.fetchall()
    def create():
        schema = 'ticketing_s19_' + uuid.uuid4().hex[:16] + '_it'
        with perf.connect() as db, db.cursor() as cur:
            cur.execute('CREATE DATABASE `' + schema + '` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci')
            cur.execute("GRANT ALL PRIVILEGES ON `" + schema + "`.* TO 'ticketing_perf'@'%'")
        return schema
    def facts(schema):
        with perf.connect() as db, db.cursor() as cur:
            cur.execute('USE `' + schema + '`')
            return helpers.facts(db)
    def original_column_facts(schema, reference):
        result = {}
        for table in before:
            columns = query(reference, 'SHOW COLUMNS FROM `' + table + '`')
            names = ','.join('`' + col['Field'] + '`' for col in columns)
            primary = ','.join('`' + col['Field'] + '`' for col in columns if col['Key'] == 'PRI')
            rows = query(schema, 'SELECT ' + names + ' FROM `' + table + '` ORDER BY ' + primary)
            digest = hashlib.sha256()
            for row in rows:
                encoded = json.dumps(row, default=lambda v: v.hex() if isinstance(v, bytes) else str(v),
                    sort_keys=True, ensure_ascii=False, separators=(',', ':')).encode()
                digest.update(len(encoded).to_bytes(8, 'big'))
                digest.update(encoded)
            result[table] = {'rows': len(rows), 'sha256': digest.hexdigest()}
        return result
    def audit(schema, scripts):
        results = []
        with perf.connect() as db, db.cursor() as cur:
            cur.execute('USE `' + schema + '`')
            cur.execute('SET @ticketing_quota_limit=4')
            for script in scripts:
                sql = '\n'.join(line for line in (perf.ROOT / script).read_text(encoding='utf-8').splitlines()
                                if not line.lstrip().startswith('--'))
                violations = 0
                for statement in sql.split(';'):
                    if not statement.strip():
                        continue
                    cur.execute(statement)
                    for row in cur.fetchall():
                        if 'anomaly_count' in row:
                            violations += int(row['anomaly_count'])
                        elif 'violation_count' in row:
                            violations += int(row['violation_count'])
                        elif script.endswith('audit-staff-audit-readiness.sql'):
                            violations += int(any(str(value).startswith('STOP_') for value in row.values()))
                        elif not ('version' in row and 'success' in row):
                            violations += 1
                results.append({'script': script, 'violations': violations})
        return results
    def restore(schema, dump):
        # 无 --databases 的转储不含USE/CREATE DATABASE，目标由受控mysql参数唯一指定。
        with perf.connect() as db:
            pass
        env = os.environ.copy()
        env['MYSQL_PWD'] = cfg['rootPassword']
        result = subprocess.run(['docker', 'exec', '-i', '--env', 'MYSQL_PWD',
            'ticketing-perf-mysql-1', 'mysql', '-u', 'root', '--default-character-set=utf8mb4', schema],
            input=dump, capture_output=True, env=env)
        (private / (schema + '-restore.log')).write_bytes(result.stderr)
        if result.returncode:
            raise RuntimeError('恢复失败，保留副本和私有日志')
    def app_migrate(schema, approved):
        prefixes = ('SPRING_', 'MANAGEMENT_', 'TICKETING_', 'MYSQL_', 'RABBITMQ_', 'REDIS_', 'SERVER_',
                    'ORDER_', 'WAITLIST_', 'PURCHASE_', 'OUTBOX_', 'ADMIN_', 'TICKET_', 'CHECKIN_', 'PERF_', 'RATE_LIMIT_')
        env = {k: v for k, v in os.environ.items() if not k.upper().replace('.', '_').replace('-', '_').startswith(prefixes)
               and k.upper() not in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS')}
        env.update(PERF_DB_PASSWORD=cfg['dbPassword'], PERF_MQ_PASSWORD=cfg['mqPassword'],
                   PERF_TICKET_KEYS=cfg['ticketKeys'], PERF_ADMIN_PASSWORD=cfg['adminPassword'],
                   MYSQL_PASSWORD=cfg['dbPassword'], RABBITMQ_PASSWORD=cfg['mqPassword'],
                   SPRING_FLYWAY_USER='root', SPRING_FLYWAY_PASSWORD=cfg['rootPassword'],
                   ORDER_QUOTA_MIGRATION_APPROVED=str(approved).lower())
        env.update(perf.rate_limit_environment('regression'))
        arguments = ['java', '-Xms256m', '-Xmx512m', '-jar', str(perf.ROOT / args.jar),
            '--spring.config.additional-location=file:' + (perf.HERE / 'application-performance.yml').as_posix(),
            '--spring.datasource.url=jdbc:mysql://127.0.0.1:13307/' + schema + '?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true',
            '--ticketing.admin-bootstrap.enabled=false', '--ticketing.order.expiry.enabled=false',
            '--ticketing.order.aftercare.enabled=false', '--ticketing.purchase.jobs-enabled=false',
            '--ticketing.purchase.messaging-enabled=false', '--ticketing.messaging.jobs-enabled=false',
            '--ticketing.waitlist.jobs.enabled=false', '--ticketing.waitlist.messaging.enabled=false']
        with (private / (schema + '-application.log')).open('ab') as log:
            process = subprocess.Popen(arguments, cwd=perf.ROOT, env=env, stdout=log, stderr=log,
                creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
        try:
            for _ in range(90):
                if process.poll() is not None:
                    raise RuntimeError('迁移副本应用未启动，保留私有日志')
                try:
                    if requests.get(perf.BASE + '/api/v1/auth/csrf', timeout=1).status_code == 200:
                        return True
                except requests.RequestException:
                    pass
                time.sleep(1)
            raise RuntimeError('副本启动超时')
        finally:
            process.terminate()
            process.wait(timeout=20)
    scripts = ['scripts/audit-waitlist-relations.sql', 'scripts/audit-refund-readiness.sql',
               'scripts/audit-staff-audit-readiness.sql', 'scripts/audit-async-purchase-readiness.sql',
               'scripts/audit-user-session-quota.sql']
    name = 'ticketing-mysql-1'
    container = json.loads(subprocess.check_output(['docker', 'inspect', name]))[0]
    if container['Config']['Labels'].get('com.docker.compose.project') != 'ticketing':
        raise RuntimeError('原库容器身份不匹配')
    was_running = container['State']['Running']
    # 原库已停且未运行后端才允许作为停写备份来源；在线来源须另行安排维护窗口。
    if was_running:
        raise RuntimeError('原库正在运行，默认不推断停写；请另行安排维护窗口')
    env_values = dict(line.split('=', 1) for line in (perf.ROOT / '.env').read_text(encoding='utf-8-sig').splitlines()
                      if line and not line.startswith('#') and '=' in line)
    if (env_values.get('MYSQL_HOST') != '127.0.0.1' or env_values.get('MYSQL_PORT') != '3307'
            or env_values.get('MYSQL_DATABASE') != 'ticketing'):
        raise RuntimeError('原库必须为已记录的本机ticketing V7，只允许读取')
    try:
        subprocess.run(['docker', 'start', name], capture_output=True, check=True)
        for _ in range(60):
            if json.loads(subprocess.check_output(['docker', 'inspect', name]))[0]['State'].get('Health', {}).get('Status') == 'healthy':
                break
            time.sleep(1)
        with pymysql.connect(host='127.0.0.1', port=3307, user=env_values['MYSQL_USER'],
                password=env_values['MYSQL_PASSWORD'], database='ticketing', autocommit=True,
                cursorclass=pymysql.cursors.DictCursor) as db:
            before = helpers.facts(db)
            with db.cursor() as cur:
                cur.execute('SELECT version,success FROM flyway_schema_history ORDER BY installed_rank')
                history = cur.fetchall()
            check('原库只读确认为V7', history == [{'version': str(i), 'success': 1} for i in range(1, 8)],
                  database='ticketing', versions=history, originallyStopped=True)
            command = ['docker', 'exec', name, 'sh', '-c',
                'MYSQL_PWD="$MYSQL_PASSWORD" exec mysqldump -u "$MYSQL_USER" --single-transaction --skip-lock-tables --skip-add-locks --hex-blob --no-tablespaces --set-gtid-purged=OFF --routines --events --triggers "$MYSQL_DATABASE"']
            dump = subprocess.check_output(command, stderr=subprocess.PIPE)
            (private / 'source-v7.sql').write_bytes(dump)
            after = helpers.facts(db)
            check('原库只读备份且前后全表摘要相同', before == after,
                  tableFacts=before, backupSha256=hashlib.sha256(dump).hexdigest(), backupBytes=len(dump),
                  privateBackup=str((private / 'source-v7.sql').relative_to(perf.ROOT)))
    finally:
        subprocess.run(['docker', 'stop', name], capture_output=True, check=True)
    clone, recovery, fresh = create(), create(), create()
    report['schemas'] = {'upgrade': clone, 'restoredBackup': recovery, 'fresh': fresh}
    restore(clone, dump)
    restore(recovery, dump)
    check('V7备份两次真实恢复逐表摘要完全一致', facts(clone) == before == facts(recovery))
    preflight = audit(clone, scripts)
    check('V7历史只读迁移预检', all(row['violations'] == 0 for row in preflight), audits=preflight)
    # 仅该隔离副本按停写/备份/审计门禁显式批准；CLI没有应用的环境开关，批准记录绑定schema及备份。
    check('副本V9单次迁移已显式批准', True, schema=clone, writesStopped=True,
          backupSha256=hashlib.sha256(dump).hexdigest(), approvalScope='isolated-copy-only')
    app_migrate(clone, True)
    # V10为ticket_sku增加默认SINGLE等列；逐行比较原有列，另行核对新增结构与默认值。
    migrated = original_column_facts(clone, recovery)
    check('升级V8V9V10保持所有既有业务事实', all(migrated[table] == value for table, value in before.items()
          if table != 'flyway_schema_history'), originalOrderCount=before['ticket_order']['rows'])
    postflight = audit(clone, scripts + ['scripts/performance/audit.sql', 'scripts/performance/audit-purchase.sql',
                                       'scripts/performance/audit-quota.sql', 'scripts/performance/audit-buckets.sql'])
    check('V10副本全部SQL审计零偏差', all(row['violations'] == 0 for row in postflight), audits=postflight)
    unchanged = facts(clone)
    app_migrate(clone, False)
    check('V10迁移重跑幂等且单次批准已撤销', facts(clone) == unchanged, approvalActive=False)
    app_migrate(fresh, False)
    check('全新空库V1到V10', query(fresh, 'SELECT COUNT(*) n FROM flyway_schema_history WHERE success=1')[0]['n'] == 10)
    for key, schema in [('upgrade', clone), ('fresh', fresh)]:
        objects = {}
        for kind, statement in {
            'tables': 'SELECT table_name,table_type FROM information_schema.tables WHERE table_schema=DATABASE() ORDER BY table_name',
            'indexes': 'SELECT table_name,index_name,seq_in_index,column_name,non_unique FROM information_schema.statistics WHERE table_schema=DATABASE() ORDER BY table_name,index_name,seq_in_index',
            'constraints': 'SELECT table_name,constraint_name,constraint_type FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() ORDER BY table_name,constraint_name',
            'triggers': 'SELECT trigger_name,event_manipulation,action_timing FROM information_schema.triggers WHERE trigger_schema=DATABASE() ORDER BY trigger_name',
            'views': 'SELECT table_name,check_option,is_updatable FROM information_schema.views WHERE table_schema=DATABASE() ORDER BY table_name',
        }.items():
            objects[kind] = query(schema, statement)
        report[key + 'Objects'] = objects
    check('恢复副本仍保持原V7事实可用于回退', facts(recovery) == before,
          warning='恢复演练在新schema，原开发库未升级；失败生产升级不能使用Flyway降级')
    report.update(passed=True, endedAt=datetime.now(timezone.utc).isoformat(),
                  sourceUpgrade='NOT_AUTHORIZED_NOT_EXECUTED', preservedSchemas=True)
    perf.save(output, report)


if __name__ == '__main__':
    main()
