"""第16阶段四轮闭环；基线及优化分别使用新标签、相同k6与隔离数据规模。"""
import argparse
import hashlib
import gzip
import json
import re
import subprocess
import zipfile
from types import SimpleNamespace
import perf


def quota_upgrade(jar, directory):
    """仅固定隔离库受控切换；业务应用已停，先审计并备份，绝不操作开发库。"""
    with zipfile.ZipFile(jar) as archive:
        if 'BOOT-INF/classes/db/migration/V9__user_session_quota.sql' not in archive.namelist():
            return {}
    with perf.connect() as db, db.cursor() as cur:
        cur.execute("SELECT COUNT(*) n FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='user_session_quota'")
        if cur.fetchone()['n']:
            return {}
        cur.execute('SET @ticketing_quota_limit=4')
        sql='\n'.join(line for line in (perf.ROOT/'scripts/audit-user-session-quota.sql').read_text(encoding='utf-8').splitlines() if not line.lstrip().startswith('--'))
        checks=[]
        for statement in sql.split(';'):
            if statement.strip():
                cur.execute(statement);checks.extend(cur.fetchall())
        assert all(row['anomaly_count']==0 for row in checks),checks
    container=json.loads(subprocess.check_output(['docker','inspect','ticketing-perf-mysql-1'],text=True))[0]
    assert container['Config']['Labels']['com.docker.compose.project']=='ticketing-perf'
    dump=subprocess.check_output(['docker','exec','ticketing-perf-mysql-1','sh','-c',
        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -u root --single-transaction --skip-lock-tables --set-gtid-purged=OFF --routines --triggers --databases ticketing_perf'],stderr=subprocess.PIPE)
    backup=perf.PRIVATE/'backups'/(directory.name+'-before-v9.sql.gz')
    backup.parent.mkdir(parents=True,exist_ok=True)
    if backup.exists():raise RuntimeError('备份已存在，禁止覆盖')
    backup.write_bytes(gzip.compress(dump))
    assert gzip.decompress(backup.read_bytes())==dump
    perf.save(directory/'quota-upgrade-preflight.json',{'environment':'ticketing-perf-only','applicationStopped':True,
        'checks':checks,'backup':str(backup.relative_to(perf.ROOT)),'backupBytes':len(dump),
        'backupSha256':hashlib.sha256(dump).hexdigest(),'backupVerified':True,'maxPerUserSession':4})
    return {'ORDER_QUOTA_MIGRATION_APPROVED':'true'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', required=True)
    parser.add_argument('--label', required=True)
    parser.add_argument('--scenarios', nargs='+', choices=['hot-ample','hot-scarce','mixed','same-user-cross-session'],
                        help='省略时四轮齐全；诊断复测可显式选择场景，新标签独立保存')
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,63}', args.label):
        raise RuntimeError('标签不合法')
    directory = perf.RESULTS / args.label
    if directory.exists():
        raise RuntimeError('结果目录已存在，禁止覆盖')
    perf.connect().close()
    perf.app_stop()
    approved=quota_upgrade(args.jar,directory)
    perf.app_start(args.jar, rate_limit_mode='regression', CATALOG_CACHE_ENABLED='true', PURCHASE_ADMISSION_LIMIT_ENABLED='false',**approved)
    original = perf.RESULTS
    try:
        perf.RESULTS = directory
        perf.environment_snapshot()
    finally:
        perf.RESULTS = original
    rows = []
    selected=[(scenario,vus) for scenario,vus in [('hot-ample',100),('hot-scarce',100),('mixed',25),('same-user-cross-session',25)]
              if args.scenarios is None or scenario in args.scenarios]
    for scenario, vus in selected:
        mode = 'mixed' if scenario == 'same-user-cross-session' else scenario
        fixture = perf.prepare(mode, vus)
        if scenario == 'same-user-cross-session':
            fixture['users'] = [fixture['users'][0]] * vus
            fixture['sharedUserScenario'] = True
            perf.save(perf.PRIVATE / 'input.json', fixture)
        try:
            perf.RESULTS = directory
            perf.run(SimpleNamespace(label=scenario, scenario=mode, vus=vus, warmup=10, duration=20, k6=None,
                                    activity_id=None, session_id=None, sku_id=None, admission_rejections=False))
        finally:
            perf.RESULTS = original
        path = directory / scenario / (mode + '-' + str(vus))
        environment = json.loads((path / 'environment.json').read_text(encoding='utf-8'))
        metrics = json.loads((path / 'k6-summary.json').read_text(encoding='utf-8'))['metrics']
        def count(name): return metrics.get(name, {}).get('values', {}).get('count', 0)
        def latency(name):
            values = metrics.get(name, {}).get('values', {})
            return {key: values.get(key) for key in ('med','p(95)','p(99)','max')}
        rows.append({'scenario':scenario, 'vus':vus, 'distinctBuyerAccounts':1 if fixture.get('sharedUserScenario') else vus,
                     'sessions':len(fixture.get('mixed', [fixture['target']])),
                     'httpRps':count('steady_requests')/20, 'acceptedTps':count('accepted_purchase_requests')/20,
                     'orderTps':count('created_orders')/20, 'completionMs':latency('steady_purchase_completion_latency'),
                     'serverErrors':count('server_errors'), 'transportErrors':count('transport_errors'),
                     'mysqlDelta':environment['mysqlDelta'], 'final':environment['final'],
                     'artifactSha256':environment['app']['sha256'], 'auditPassed':environment['audit']['passed'],
                     'k6ExitCode':environment['k6ExitCode'], 'raw':str(path.relative_to(perf.ROOT))})
        perf.save(directory / 'comparison.json', {'rounds':rows, 'complete':False})
    assert len(rows)==len(selected) and len({r['artifactSha256'] for r in rows})==1
    perf.save(directory / 'comparison.json', {
        'environment':'ticketing-perf-only', 'rounds':rows, 'complete':True,
        'scenarioScriptSha256':hashlib.sha256((perf.HERE/'scenarios.js').read_bytes()).hexdigest(),
        'passed':all(r['auditPassed'] and r['k6ExitCode']==0 and r['serverErrors']==r['transportErrors']==0 for r in rows)})


if __name__ == '__main__':
    main()
