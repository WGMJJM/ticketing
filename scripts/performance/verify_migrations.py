"""仅在有随机标记的专用MySQL中验证V4历史升级、V5空库和异常关联预检。"""
import argparse
import hashlib
import os
from pathlib import Path
import re
import subprocess
import time
import uuid

import requests
import perf

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--legacy-jar', required=True, help='基准2c7b086构建的V4产物')
    parser.add_argument('--jar', required=True, help='本次包含V5的构建产物')
    parser.add_argument('--label', default='stage9')
    args = parser.parse_args()
    if not re.fullmatch(r'[a-zA-Z0-9_-]{1,50}', args.label):
        raise RuntimeError('结果标签格式不正确')
    result_path = perf.ROOT/'docs/architecture/results'/f'migration-{args.label}.json'
    if result_path.exists():
        raise RuntimeError('结果已存在，请使用新标签')
    perf.connect().close()
    cfg = perf.config()
    schemas = []
    report = {'passed': False, 'checks': [], 'artifacts': {}}
    for name, path in [('legacy', args.legacy_jar), ('current', args.jar)]:
        report['artifacts'][name] = {'sha256': hashlib.sha256(Path(path).read_bytes()).hexdigest()}
    migration_dir = perf.ROOT/'backend/src/main/resources/db/migration'
    for path in sorted(migration_dir.glob('V[1-4]__*.sql')):
        expected = subprocess.check_output(['git', 'show', f'2c7b086:{path.relative_to(perf.ROOT).as_posix()}'])
        assert path.read_bytes().replace(b'\r\n', b'\n') == expected, 'V1至V4不得修改'

    def sql(schema, statement, values=None):
        assert schema in schemas and re.fullmatch(r'ticketing_perf_migration_[a-f0-9]{8}_[a-z]+', schema)
        with perf.connect() as db, db.cursor() as cursor:
            cursor.execute(f'USE `{schema}`')
            cursor.execute(statement, values)
            return cursor.fetchall()

    def create(case):
        schema = 'ticketing_perf_migration_' + uuid.uuid4().hex[:8] + '_' + case
        with perf.connect() as db, db.cursor() as cursor:
            cursor.execute(f'CREATE DATABASE `{schema}` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci')
            cursor.execute(f"GRANT ALL PRIVILEGES ON `{schema}`.* TO 'ticketing_perf'@'%'")
        schemas.append(schema)
        return schema

    def start(jar, schema):
        # 与主压测进程不同端口；不接收任意数据库、远程地址或调用方JVM注入。
        prefixes = ('SPRING_', 'MANAGEMENT_', 'TICKETING_', 'MYSQL_', 'RABBITMQ_', 'REDIS_', 'SERVER_',
                    'ORDER_', 'WAITLIST_', 'ADMIN_', 'SESSION_', 'TICKET_', 'CHECKIN_', 'LOGGING_')
        env = {key: value for key, value in os.environ.items()
               if not key.upper().replace('.', '_').replace('-', '_').startswith(prefixes)
               and key.upper() not in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS')}
        env.update(PERF_DB_PASSWORD=cfg['dbPassword'], PERF_MQ_PASSWORD=cfg['mqPassword'],
                   PERF_ADMIN_PASSWORD=cfg['adminPassword'], PERF_TICKET_KEYS=cfg['ticketKeys'])
        arguments = ['java', '-Xms256m', '-Xmx512m', '-jar', str(Path(jar).resolve()),
                     '--spring.config.additional-location=file:' + (perf.HERE/'application-performance.yml').as_posix(),
                     '--server.port=18081',
                     '--spring.datasource.url=jdbc:mysql://127.0.0.1:13307/' + schema + '?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&characterEncoding=UTF-8',
                     '--ticketing.waitlist.jobs.enabled=false', '--ticketing.waitlist.messaging.enabled=false',
                     '--ticketing.order.expiry.enabled=false']
        logfile = perf.PRIVATE/f'migration-{args.label}.log'
        with logfile.open('ab') as output:
            process = subprocess.Popen(arguments, cwd=perf.ROOT, env=env, stdout=output, stderr=output,
                                       creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
        try:
            for _ in range(90):
                if process.poll() is not None:
                    raise RuntimeError('迁移探针应用未启动，详见私有日志')
                try:
                    if requests.get('http://127.0.0.1:18081/api/v1/auth/csrf', timeout=1).status_code == 200:
                        return process
                except requests.RequestException:
                    pass
                time.sleep(1)
            raise RuntimeError('迁移探针启动超时')
        except BaseException:
            process.terminate()
            process.wait(timeout=20)
            raise

    def stop(process):
        process.terminate()
        process.wait(timeout=20)

    def version(schema):
        return sql(schema, 'SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank DESC LIMIT 1')[0]['version']

    def seed(schema, wrong_sku=False):
        statements = [
            "INSERT INTO user_account(id,username,password_hash) VALUES(100,'migration_fixture','不可登录的测试占位值')",
            "INSERT INTO activity(id,title,category,city,venue,status) VALUES(100,'迁移测试','CONCERT','北京','独立场馆','PUBLISHED')",
            "INSERT INTO activity_session(id,activity_id,title,starts_at,ends_at,sale_starts_at) VALUES(100,100,'迁移场次',UTC_TIMESTAMP(6)+INTERVAL 1 DAY,UTC_TIMESTAMP(6)+INTERVAL 2 DAY,UTC_TIMESTAMP(6)-INTERVAL 1 DAY)",
            "INSERT INTO ticket_sku(id,session_id,name,price_cents,total_stock,locked_stock,sale_starts_at,sale_ends_at) VALUES(100,100,'预留票档',12800,10,2,UTC_TIMESTAMP(6)-INTERVAL 1 HOUR,UTC_TIMESTAMP(6)+INTERVAL 1 HOUR)",
            "INSERT INTO ticket_sku(id,session_id,name,price_cents,total_stock,sale_starts_at,sale_ends_at) VALUES(101,100,'其他票档',12800,10,UTC_TIMESTAMP(6)-INTERVAL 1 HOUR,UTC_TIMESTAMP(6)+INTERVAL 1 HOUR)",
            "INSERT INTO ticket_order(id,order_no,user_id,session_id,total_amount_cents,idempotency_operation,idempotency_key,request_hash,expires_at) VALUES(100,'migration-order',100,100,25600,'WAITLIST_CONFIRM','waitlist-100',UNHEX(SHA2('100:2',256)),UTC_TIMESTAMP(6)+INTERVAL 15 MINUTE)",
            "INSERT INTO ticket_order_item(order_id,sku_id,session_id,quantity,unit_price_cents,sku_name_snapshot) VALUES(100,100,100,2,12800,'预留票档')",
        ]
        for statement in statements:
            sql(schema, statement)
        sql(schema, "INSERT INTO waitlist_entry(id,user_id,sku_id,quantity,status,reserved_order_id,expires_at) VALUES(100,100,%s,2,'ALLOCATED',100,UTC_TIMESTAMP(6)+INTERVAL 1 HOUR)", (101 if wrong_sku else 100,))

    def facts(schema):
        return sql(schema, 'SELECT w.id,w.user_id,w.sku_id,w.quantity,w.status,w.reserved_order_id,o.status order_status,o.total_amount_cents,t.locked_stock,t.sold_stock FROM waitlist_entry w JOIN ticket_order o ON o.id=w.reserved_order_id JOIN ticket_sku t ON t.id=100')

    audit_sql = '\n'.join(line for line in (perf.ROOT/'scripts/audit-waitlist-relations.sql').read_text(encoding='utf-8').splitlines() if not line.startswith('--'))
    try:
        fresh = create('fresh')
        process = start(args.jar, fresh)
        try:
            assert version(fresh) == '5'
            assert sql(fresh, "SELECT COUNT(*) n FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name<>'flyway_schema_history'")[0]['n'] == 10
            assert sql(fresh, "SELECT COUNT(*) n FROM information_schema.referential_constraints WHERE constraint_schema=DATABASE() AND constraint_name='fk_waitlist_order_sku'")[0]['n'] == 1
            report['checks'].append({'case': '全新独立库V1至V5', 'passed': True, 'domainTables': 10})
        finally:
            stop(process)

        upgrade = create('upgrade')
        process = start(args.legacy_jar, upgrade)
        try:
            assert version(upgrade) == '4'
            seed(upgrade)
            before = facts(upgrade)
            assert not sql(upgrade, audit_sql)
        finally:
            stop(process)
        process = start(args.jar, upgrade)
        try:
            assert version(upgrade) == '5' and facts(upgrade) == before
            assert not sql(upgrade, audit_sql)
            report['checks'].append({'case': 'V4历史预留记录升级V5', 'passed': True, 'businessFactsUnchanged': True})
        finally:
            stop(process)

        dirty = create('dirty')
        process = start(args.legacy_jar, dirty)
        try:
            seed(dirty, wrong_sku=True)
            before = facts(dirty)
            differences = sql(dirty, audit_sql)
            assert len(differences) == 1 and version(dirty) == '4' and facts(dirty) == before
            report['checks'].append({'case': '不一致历史记录只审计并停止', 'passed': True,
                                     'violations': differences, 'migrationAttempted': False, 'businessFactsUnchanged': True})
        finally:
            stop(process)
        report['passed'] = True
        print('V5空库、V4历史升级及异常关联只读预检均通过', flush=True)
    finally:
        perf.save(result_path, report)
        # 只清理本进程创建并列入清单的临时探针库，不改动现有ticketing_perf或开发库。
        for schema in schemas:
            assert re.fullmatch(r'ticketing_perf_migration_[a-f0-9]{8}_[a-z]+', schema)
            with perf.connect() as db, db.cursor() as cursor:
                cursor.execute(f'DROP DATABASE `{schema}`')
                cursor.execute(f"REVOKE ALL PRIVILEGES ON `{schema}`.* FROM 'ticketing_perf'@'%'")


if __name__ == '__main__':
    main()
