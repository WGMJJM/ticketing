"""V7只读诊断、完整备份恢复验证及显式授权的原迁移重放。默认只读，不清库。"""
import argparse
import binascii
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import uuid

import pymysql

ROOT = Path(__file__).resolve().parents[1]
MIGRATIONS = ROOT / 'backend/src/main/resources/db/migration'
V7_TABLES = ('staff_session_permission', 'admin_audit_log')


def identifier(value):
    if not re.fullmatch(r'[A-Za-z][A-Za-z0-9_]{0,63}', value or ''):
        raise RuntimeError('数据库/表标识无效')
    return value


def connect(args, database=None):
    return pymysql.connect(host=args.host, port=args.port, user=args.user,
                           password=os.environ.get('MYSQL_PASSWORD', ''),
                           database=identifier(database or args.database), charset='utf8mb4', autocommit=True,
                           cursorclass=pymysql.cursors.DictCursor, connect_timeout=5, read_timeout=60)


def checksum(path):
    # 与当前Flyway 11.7.2 ChecksumCalculator相同：去BOM、逐行UTF-8，不包含换行。
    raw = ''.join(path.read_text(encoding='utf-8-sig').splitlines()).encode('utf-8')
    value = binascii.crc32(raw)
    return value if value < 2**31 else value - 2**32


def history_checksums():
    return {p.name.split('__')[0][1:]: checksum(p) for p in MIGRATIONS.glob('V*__*.sql')}


def facts(db):
    result = {}
    with db.cursor() as cur:
        cur.execute('SET TRANSACTION ISOLATION LEVEL REPEATABLE READ')
        cur.execute('START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY')
        cur.execute('SHOW FULL TABLES WHERE Table_type=\'BASE TABLE\'')
        tables = [next(iter(r.values())) for r in cur.fetchall()]
        for table in sorted(tables):
            identifier(table)
            cur.execute(f'SHOW COLUMNS FROM `{table}`')
            columns = cur.fetchall()
            primary = [r['Field'] for r in columns if r['Key'] == 'PRI']
            if not primary:
                raise RuntimeError('发现无主键表，须人工设计完整数据核验')
            cur.execute(f'SELECT * FROM `{table}` ORDER BY ' + ','.join('`'+identifier(p)+'`' for p in primary))
            digest = hashlib.sha256()
            count = 0
            for row in cur:
                # 不将行、口令哈希、票券摘要或Session写入共享报告。
                encoded = json.dumps(row, default=lambda v: v.hex() if isinstance(v, bytes) else str(v),
                                     sort_keys=True, ensure_ascii=False, separators=(',', ':')).encode()
                digest.update(len(encoded).to_bytes(8, 'big')); digest.update(encoded)
                count += 1
            result[table] = {'rows': count, 'sha256': digest.hexdigest()}
        db.commit()
    return result


def audit(db):
    with db.cursor() as cur:
        cur.execute('SELECT @@server_uuid serverUuid, DATABASE() databaseName, @@version version, '
                    '@@log_bin binlog, @@log_bin_trust_function_creators triggerPolicy')
        result = cur.fetchone()
        cur.execute('SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE()')
        tables = {r['TABLE_NAME'] if 'TABLE_NAME' in r else r['table_name'] for r in cur.fetchall()}
        result['history'] = []
        if 'flyway_schema_history' in tables:
            cur.execute('SELECT installed_rank,version,script,checksum,success FROM flyway_schema_history ORDER BY installed_rank')
            result['history'] = cur.fetchall()
        cur.execute('SHOW GRANTS')
        result['grants'] = [next(iter(r.values())) for r in cur.fetchall()]
        cur.execute("SELECT trigger_name,event_manipulation,action_timing,action_statement FROM information_schema.triggers WHERE trigger_schema=DATABASE()")
        result['triggers'] = cur.fetchall()
        result['objects'] = {}
        for table in ('user_account', *V7_TABLES):
            if table in tables:
                cur.execute(f'SHOW CREATE TABLE `{table}`')
                result['objects'][table] = cur.fetchone()['Create Table']
        expected = history_checksums()
        rows = result['history']
        drift = any(r['version'] not in expected or r['checksum'] != expected[r['version']] for r in rows)
        failed = [r for r in rows if not r['success']]
        latest = next((r['version'] for r in reversed(rows) if r['success']), None)
        if not tables:
            state = 'EMPTY'
        elif drift:
            state = 'STOP_CHECKSUM_DRIFT'
        elif failed:
            state = 'STOP_PARTIAL_V7' if len(failed) == 1 and failed[0]['version'] == '7' and latest == '6' else 'STOP_FAILED_HISTORY'
        elif latest == '6' and not any(t in tables for t in V7_TABLES):
            state = 'V6_READY'
        elif latest == '7' and all(t in tables for t in V7_TABLES):
            triggers = {r.get('TRIGGER_NAME', r.get('trigger_name')) for r in result['triggers']}
            state = 'V7_REVIEW_STRUCTURE' if {'admin_audit_no_update','admin_audit_no_delete'} <= triggers else 'STOP_V7_STRUCTURE'
        else:
            state = 'STOP_UNEXPECTED_STRUCTURE'
        result['state'] = state
        return result


def docker(args, executable, extra=(), input_data=None):
    endpoint = subprocess.check_output(['docker','context','inspect','--format','{{.Endpoints.docker.Host}}'], text=True).strip()
    if not endpoint.startswith(('npipe://', 'unix://')) or os.environ.get('DOCKER_HOST', '').startswith(('tcp:', 'ssh:')):
        raise RuntimeError('恢复仅允许本机Docker')
    info = json.loads(subprocess.check_output(['docker','inspect',args.container], text=True))[0]
    ports = info['NetworkSettings']['Ports'].get('3306/tcp') or []
    if args.host != '127.0.0.1' or not any(p['HostIp'] == '127.0.0.1' and int(p['HostPort']) == args.port for p in ports):
        raise RuntimeError('容器与目标MySQL连接不匹配')
    env = os.environ.copy(); env['MYSQL_PWD'] = os.environ.get('MYSQL_PASSWORD', '')
    command = ['docker','exec','-i','--env','MYSQL_PWD',args.container,executable,'-u',args.user,
               '--default-character-set=utf8mb4', *extra]
    return subprocess.run(command, input=input_data, capture_output=True, env=env, check=True).stdout


def flyway(args, action, directory, target=None):
    directory.mkdir(parents=True, exist_ok=True)
    pom = directory / 'flyway-pom.xml'
    pom.write_text('''<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
<groupId>com.ticketing</groupId><artifactId>controlled-v7-recovery</artifactId><version>1</version><build><plugins>
<plugin><groupId>org.flywaydb</groupId><artifactId>flyway-maven-plugin</artifactId><version>11.7.2</version>
<configuration><url>${env.RECOVERY_JDBC_URL}</url><user>${env.MYSQL_USER}</user><password>${env.MYSQL_PASSWORD}</password>
<cleanDisabled>true</cleanDisabled><locations><location>${env.RECOVERY_MIGRATIONS}</location></locations></configuration>
<dependencies><dependency><groupId>org.flywaydb</groupId><artifactId>flyway-mysql</artifactId><version>11.7.2</version></dependency>
<dependency><groupId>com.mysql</groupId><artifactId>mysql-connector-j</artifactId><version>9.7.0</version></dependency></dependencies>
</plugin></plugins></build></project>''', encoding='utf-8')
    env = {k:v for k,v in os.environ.items() if not k.startswith('FLYWAY_') and k not in ('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','MAVEN_ARGS','MAVEN_OPTS')}
    env.update(RECOVERY_JDBC_URL=f'jdbc:mysql://{args.host}:{args.port}/{args.database}?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true',
               MYSQL_USER=args.user, RECOVERY_MIGRATIONS='filesystem:'+MIGRATIONS.as_posix())
    command = [shutil.which('mvn') or 'mvn','-f',str(pom), 'flyway:'+action]
    if target: command.append('-Dflyway.target='+target)
    with (directory/(action+'.log')).open('ab') as output:
        result = subprocess.run(command, env=env, stdout=output, stderr=output)
    if result.returncode:
        raise RuntimeError('Flyway操作失败，保留全部现场，查看私有操作日志')


def dump(args, tables=(), data_only=False):
    options = ['--single-transaction','--skip-lock-tables','--skip-add-locks','--hex-blob','--no-tablespaces',
               '--set-gtid-purged=OFF','--complete-insert','--skip-disable-keys']
    options += ['--no-create-info','--skip-triggers'] if data_only else ['--routines','--events','--triggers']
    value = docker(args, 'mysqldump', [*options, identifier(args.database), *tables])
    if data_only:
        value = ('SET FOREIGN_KEY_CHECKS=1;\n' + '\n'.join(line for line in value.decode('utf-8').splitlines()
                  if 'FOREIGN_KEY_CHECKS' not in line and 'UNIQUE_CHECKS' not in line)).encode('utf-8')
    return value


def backup(args, directory):
    directory.mkdir(parents=True, exist_ok=False)
    with connect(args) as db:
        before = facts(db); status = audit(db)
        data = dump(args)
        after = facts(db)
    if before != after: raise RuntimeError('备份期间数据发生变化，停止并重新建立停机窗口')
    (directory/'database.sql').write_bytes(data)
    manifest = {'source': {k:status[k] for k in ['serverUuid','databaseName']}, 'facts':before,
                'dumpSha256':hashlib.sha256(data).hexdigest(), 'audit':status, 'restoreVerified':False}
    save(directory/'manifest.json', manifest)
    return manifest


def verify_backup(args, directory):
    """仅在带随机guard的独立功能验收MySQL中新建克隆与参考库，绝不覆盖已有库。"""
    manifest=json.loads((directory/'manifest.json').read_text(encoding='utf-8'))
    data=(directory/'database.sql').read_bytes()
    if hashlib.sha256(data).hexdigest()!=manifest['dumpSha256']: raise RuntimeError('备份摘要不匹配')
    password=os.environ.get('RECOVERY_VERIFY_PASSWORD')
    guard=os.environ.get('RECOVERY_VERIFY_GUARD')
    if not password or not guard: raise RuntimeError('缺少专用恢复验证凭据和随机标记')
    container = os.environ.get('RECOVERY_VERIFY_CONTAINER')
    if not container or container in {args.container, 'ticketing-mysql-1'}:
        raise RuntimeError('须显式指定独立恢复验证容器，不能使用源库或业务容器')
    with pymysql.connect(host='127.0.0.1',port=13307,user='root',password=password,database='ticketing_verify') as db,db.cursor() as cur:
        cur.execute('SELECT token FROM performance_control.environment_guard')
        if cur.fetchone()[0]!=guard: raise RuntimeError('恢复验证实例隔离标记错误')
        clone='ticketing_v7_restore_'+uuid.uuid4().hex[:16]+'_it'
        reference='ticketing_v7_reference_'+uuid.uuid4().hex[:16]+'_it'
        for schema in (clone,reference): cur.execute(f'CREATE DATABASE `{schema}` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci')
    destination=argparse.Namespace(host='127.0.0.1',port=13307,user='root',database=clone,container=container)
    original=os.environ.get('MYSQL_PASSWORD')
    os.environ['MYSQL_PASSWORD']=password
    try:
        docker(destination,'mysql',[clone],data)
        with connect(destination) as db:
            if facts(db)!=manifest['facts']: raise RuntimeError('完整备份恢复后行数或逐行摘要不一致')
            clone_status=audit(db)
        destination.database=reference
        flyway(destination,'migrate',directory/'reference-flyway')
        with connect(destination) as db:
            structure={t:normalize(ddl) for t,ddl in audit(db)['objects'].items()}
        save(directory/'reference-structure.json',structure)
        manifest.update(restoreVerified=True,verification={'database':clone,'serverUuid':clone_status['serverUuid'],
                                                         'referenceDatabase':reference,'allTablesMatched':True})
        save(directory/'manifest.json',manifest)
    finally:
        if original is None: os.environ.pop('MYSQL_PASSWORD',None)
        else: os.environ['MYSQL_PASSWORD']=original


def save(path, data):
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2, default=str), encoding='utf-8')


def apply(args, directory):
    manifest = json.loads((directory/'manifest.json').read_text(encoding='utf-8'))
    backup_data = (directory/'database.sql').read_bytes()
    if (not args.apply or args.confirm_database != args.database or not args.maintenance_confirmed
            or not args.confirm_server_uuid or not manifest.get('restoreVerified')
            or hashlib.sha256(backup_data).hexdigest() != manifest['dumpSha256']):
        raise RuntimeError('缺少显式授权、维护窗口、目标身份或已验证恢复的备份')
    with connect(args) as db:
        status = audit(db)
        if status['serverUuid'] != args.confirm_server_uuid or manifest['source'] != {k:status[k] for k in ['serverUuid','databaseName']}:
            raise RuntimeError('目标身份与备份不匹配')
        if status['state'] != 'STOP_PARTIAL_V7' or not status['triggerPolicy'] or facts(db) != manifest['facts']:
            raise RuntimeError('历史、触发器策略或备份后数据变化不符合受控恢复条件')
        # 仅处理已核对的两张V7表；已有触发器/其他结构差异须人工处理，禁止猜测。
        names = {r.get('TRIGGER_NAME', r.get('trigger_name')) for r in status['triggers']}
        if names & {'admin_audit_no_update','admin_audit_no_delete'} or not all(t in status['objects'] for t in V7_TABLES):
            raise RuntimeError('不是两个表已建立且两个触发器均缺失的已验证失败分支')
        preserved = dump(args, V7_TABLES, True)
        (directory/'v7-data.sql').write_bytes(preserved)
        # 先校验V1～V7实际checksum及完整结构，再允许移除失败对象。
        reference = json.loads((directory/'reference-structure.json').read_text(encoding='utf-8'))
        for table in ('user_account', *V7_TABLES):
            if normalize(status['objects'][table]) != reference[table]:
                raise RuntimeError('结构与原迁移生成的参考结构不同，停止人工核对')
        # 在任何破坏性DDL前解析依赖并检查Flyway连接，避免此时才发现工具不可运行。
        flyway(args,'info',directory/'flyway')
        journal = {'stage':'validated','before':status,'v7DataSha256':hashlib.sha256(preserved).hexdigest()}
        save(directory/'recovery-journal.json', journal)
        with db.cursor() as cur:
            for table in V7_TABLES:
                cur.execute(f'DROP TABLE `{table}`')
        journal['stage']='partial-tables-removed'; save(directory/'recovery-journal.json',journal)
        # repair只移除已验证的失败V7历史，随后真正执行原V7，绝不手工标记成功。
        flyway(args,'repair',directory/'flyway')
        journal['stage']='failed-history-repaired'; save(directory/'recovery-journal.json',journal)
        flyway(args,'migrate',directory/'flyway')
        journal['stage']='original-v7-executed'; save(directory/'recovery-journal.json',journal)
        docker(args,'mysql',[args.database],preserved)
        with db.cursor() as cur:
            for table in V7_TABLES:
                match=re.search(r'AUTO_INCREMENT=(\d+)',status['objects'][table])
                if match: cur.execute(f'ALTER TABLE `{table}` AUTO_INCREMENT={int(match.group(1))}')
        journal['stage']='data-restored'; save(directory/'recovery-journal.json',journal)
        after = facts(db)
        if {k:v for k,v in after.items() if k!='flyway_schema_history'} != {k:v for k,v in manifest['facts'].items() if k!='flyway_schema_history'}:
            raise RuntimeError('数据核验失败，停止并保留现场')
        flyway(args,'validate',directory/'flyway')
        journal.update(stage='verified',after=audit(db),dataPreserved=True)
        save(directory/'recovery-journal.json',journal)


def normalize(ddl):
    return re.sub(r'AUTO_INCREMENT=\d+\s*', '', ddl).strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--action', choices=['audit','backup','verify-backup','recover'], default='audit')
    parser.add_argument('--host', default=os.environ.get('MYSQL_HOST','127.0.0.1'))
    parser.add_argument('--port', type=int, default=int(os.environ.get('MYSQL_PORT','3307')))
    parser.add_argument('--database', default=os.environ.get('MYSQL_DATABASE','ticketing'))
    parser.add_argument('--user', default=os.environ.get('MYSQL_USER','ticketing'))
    parser.add_argument('--container', default='ticketing-mysql-1')
    parser.add_argument('--directory', type=Path)
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('--confirm-database')
    parser.add_argument('--confirm-server-uuid')
    parser.add_argument('--maintenance-confirmed', action='store_true')
    args=parser.parse_args()
    if args.action=='audit' or (args.action=='recover' and not args.apply):
        with connect(args) as db: status=audit(db)
        print(json.dumps(status,ensure_ascii=False,indent=2,default=str))
        return
    if args.directory is None: raise RuntimeError('必须指定私有备份/恢复目录')
    if args.action=='backup': backup(args,args.directory)
    elif args.action=='verify-backup': verify_backup(args,args.directory)
    else: apply(args,args.directory)
    print('操作完成；详细证据仅写入指定私有目录')


if __name__=='__main__':
    try: main()
    except Exception as error:
        print('操作停止 '+str(error) if isinstance(error,RuntimeError) else '操作停止 '+type(error).__name__,file=sys.stderr)
        sys.exit(1)
