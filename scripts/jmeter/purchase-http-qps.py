#!/usr/bin/env python3
"""购票HTTP响应闭环：复用隔离/资源保护，只统计HTTP QPS和成功202的HTTP P99。"""
import argparse
import concurrent.futures
import csv
import hashlib
import importlib.util
import json
from pathlib import Path
import secrets
import socket
import subprocess
import time

from http_qps_stats import http_summary, http_stability, valid_window

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('diagnose_purchase', HERE/'diagnose-purchase.py')
base = importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)
PLAN = HERE/'purchase-http-qps.jmx'


def read_posts(directory):
    path = directory/'post.jtl'
    if not path.exists(): return []
    with path.open(encoding='utf-8-sig', newline='') as stream:
        return [p for p in csv.DictReader(stream) if p.get('httpCompletedMs','').isdigit()]


def inventory_notifications_drained(env, seconds=30):
    """停发后等待已初始化票档的数据库版本与Redis账本一致。"""
    deadline=time.monotonic()+seconds
    with base.db_connect(env) as db:
        base.db_guard(db,env)
        while time.monotonic()<deadline:
            with db.cursor() as cur:
                cur.execute("""SELECT t.id,COALESCE(v.inventory_version,0) version FROM ticket_sku t
                    LEFT JOIN inventory_sync_version v ON v.sku_id=t.id WHERE t.redis_inventory_initialized=1""")
                rows=cur.fetchall()
            if all((lambda ledger: ledger.get('ready')=='1' and
                    str(ledger.get('inventoryVersion'))==str(row['version']))(
                    json.loads(base.redis('HGETALL',f"ticketing:seckill:v2:ticketingjmeter:sku:{row['id']}"))) for row in rows):
                return True
            time.sleep(1)
    return False


def take_accounts(runtime, count):
    with (runtime/'measure-users.csv').open(encoding='utf-8-sig',newline='') as stream:
        accounts=list(csv.DictReader(stream))
    offset=json.loads((runtime/'next-account.json').read_text(encoding='utf-8'))
    if not 0 <= offset <= len(accounts) or len({a['username'] for a in accounts}) != len(accounts):
        raise RuntimeError('账号池或领取游标不合法')
    if offset+count > len(accounts): raise RuntimeError('独立账号预算不足，禁止复用或重置游标')
    base.save(runtime/'next-account.json',offset+count)
    return accounts[offset:offset+count], offset


def eligible_existing_accounts(args, env, metadata, count):
    """用户明确允许复用旧账号；每档仍每人一笔，保留原领取游标。"""
    with (args.runtime/'measure-users.csv').open(encoding='utf-8-sig',newline='') as stream:
        accounts=list(csv.DictReader(stream))
    chosen=[]
    with base.db_connect(env) as db:
        base.db_guard(db,env)
        with db.cursor() as cur:
            for start in range(0,len(accounts),500):
                chunk=accounts[start:start+500]
                placeholders=','.join(['%s']*len(chunk))
                cur.execute("SELECT u.username FROM user_account u LEFT JOIN user_session_quota q "
                    "ON q.user_id=u.id AND q.session_id=%s WHERE u.status='ACTIVE' AND u.role='USER' "
                    "AND u.email_verified_at IS NOT NULL AND COALESCE(q.occupied_quantity,0)<4 "
                    f"AND u.username IN ({placeholders})",[metadata['sessionId'],*[a['username'] for a in chunk]])
                eligible={row['username'] for row in cur.fetchall()}
                for index,row in enumerate(chunk,start):
                    if index not in args.reused_account_indices and row['username'] in eligible:
                        chosen.append(dict(row,_accountIndex=index))
                        if len(chosen)==count:
                            args.reused_account_indices.update(a['_accountIndex'] for a in chosen)
                            return chosen
    raise RuntimeError('当前有效且有剩余额度的独立旧账号不足，不创建或重复刷单')


def tier(args, directory, backend, env, metadata, threads, selftest=False):
    directory.mkdir()
    monitor=base.Monitor(env,directory,backend.pid,metadata['skuId'])
    monitor.start()
    process=None; output=None
    try:
        if not base.drain(env,30)['complete']: raise RuntimeError('前档尚未排空')
        count=10 if selftest else args.accounts_per_tier
        if args.reuse_accounts:
            selected=eligible_existing_accounts(args,env,metadata,count);offset=None
        else:
            selected,offset=take_accounts(args.runtime,count)
        sessions=[]
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
            for value in pool.map(base.login,selected):
                value['accountIndex']=selected[len(sessions)]['_accountIndex'] if args.reuse_accounts else offset+len(sessions)
                sessions.append(value)
                if len(sessions)%1000==0: print(f'{threads}线程预登录 {len(sessions)}/{len(selected)}',flush=True)
                if monitor.stop.exists(): raise RuntimeError('预登录期间资源保护触发')
        batch='httpqps-'+secrets.token_hex(8)
        start_load=int(time.time()*1000)+12000
        start=start_load+(0 if selftest else args.warmup_seconds*1000)
        end=start+args.seconds*1000
        fallback_end=start_load+max(60,args.warmup_seconds+30)*1000+args.seconds*1000
        if selftest: fallback_end=end
        monitor.measurement_start_ms=start_load
        base.save(directory/'sessions.json',dict(target='http://127.0.0.1:18080/api/v1/orders',batch=batch,
            skuId=metadata['skuId'],sessions=sessions,startMs=start_load,endMs=fallback_end,
            controlFile=str(directory/'control.json'),gateLimit=500,stopAfter=5 if selftest else 0))
        record=dict(batch=batch,threads=threads,accounts=len(sessions),accountOffset=offset,
            accountSource='existing_eligible_accounts' if args.reuse_accounts else 'unclaimed_accounts',
            bucketCount=metadata['bucketCount'],skuId=metadata['skuId'],consumers=12,prefetch=1,pool=16,
            capacity=1000,loadStartMs=start_load,measurementConfirmed=selftest,
            startMs=start,endMs=end,gateLimit=500,model='HTTP-response-closed-loop',
            planSha256=hashlib.sha256(PLAN.read_bytes()).hexdigest(),
            artifactSha256=hashlib.sha256((base.ROOT/'backend/target/ticketing-backend-0.1.0-SNAPSHOT.jar').read_bytes()).hexdigest())
        base.save(directory/'run.json',record)
        base.publish_flow(directory/'control.json',dict(timeMs=int(time.time()*1000),pending=0,endMs=fallback_end))
        command=[args.java,'-Xms64m','-Xmx256m','-XX:+UseG1GC',f'-Xlog:gc:file={directory/"jmeter-gc.log"}',
            '-jar',args.jmeter_jar,'-n','-t',str(PLAN),f'-Jthreads={threads}',
            f'-JsessionsFile={directory/"sessions.json"}',f'-JstopFile={monitor.stop}',
            '-Jsample_variables=postStartMs,httpCompletedMs,postElapsedMs,idempotencyKey,accountIndex,requestId,failureCode',
            '-Jjmeter.save.saveservice.output_format=csv','-Jjmeter.save.saveservice.print_field_names=true',
            '-Jjmeter.save.saveservice.autoflush=true','-Jjmeter.save.saveservice.response_data=false',
            '-Jjmeter.save.saveservice.samplerData=false','-Jjmeter.save.saveservice.responseHeaders=false',
            '-Jjmeter.save.saveservice.requestHeaders=false','-l',str(directory/'post.jtl'),'-j',str(directory/'jmeter.log')]
        output=(directory/'jmeter-cli.log').open('w',encoding='utf-8')
        process=subprocess.Popen(command,cwd=base.ROOT,stdout=output,stderr=subprocess.STDOUT,
                                 creationflags=subprocess.CREATE_NO_WINDOW)
        backend.http_qps_sender_active=True
        monitor.jmeter_pid=process.pid;monitor.jmeter_process=process
        next_check=start_load+args.warmup_seconds*1000
        final_end=fallback_end
        with socket.create_connection(('127.0.0.1',16379),timeout=1) as sock:
            sock.settimeout(1)
            with sock.makefile('rb') as reader:
                while process.poll() is None:
                    now=int(time.time()*1000)
                    pending=int(base.redis_command(sock,reader,'GET','ticketing:seckill:v2:ticketingjmeter:pending') or 0)
                    if pending>=500: monitor.trip('http_capacity_gate_reached_50_percent')
                    base.publish_flow(directory/'control.json',dict(timeMs=now,pending=pending,endMs=final_end))
                    if not selftest and not record['measurementConfirmed'] and now>=next_check and not monitor.stop.exists():
                        stability=http_stability(read_posts(directory),monitor.samples,now)
                        print(f'{threads}线程预热稳定性 '+json.dumps(stability),flush=True)
                        if stability['stable']:
                            start=now+1000;end=start+args.seconds*1000;final_end=end
                            record.update(startMs=start,endMs=end,measurementConfirmed=True,warmupSeconds=(start-start_load)/1000)
                            base.save(directory/'run.json',record)
                            print(f'{threads}线程不断流进入{args.seconds}秒HTTP测量窗口',flush=True)
                        elif now>=start_load+max(60,args.warmup_seconds+30)*1000:
                            monitor.trip('http_warmup_not_stable')
                        next_check=now+5000
                    if now>final_end+20000: monitor.trip('http_jmeter_deadline_exceeded')
                    if now>final_end+40000: raise RuntimeError('JMeter未退出，保留现场')
                    time.sleep(.1)
        monitor.jmeter_pid=None
        backend.http_qps_sender_active=False
        output.close();output=None
        drained=base.drain(env,120)
        inventory_drained=inventory_notifications_drained(env)
        with base.db_connect(env) as db:
            base.db_guard(db,env)
            db.begin()
            orders,checks=base.audit(db,env,metadata,batch)
            db.rollback()
        posts=read_posts(directory)
        monitor.close()
        stop=json.loads(monitor.stop.read_text(encoding='utf-8')) if monitor.stop.exists() else {}
        result=http_summary(posts,start,end,stop.get('timeMs'))
        by_id={str(r['id']):r for r in orders}
        finalized=all(p['requestId'] in by_id and by_id[p['requestId']]['status']=='SUCCEEDED'
            and by_id[p['requestId']]['order_id'] is not None for p in posts if p['responseCode']=='202')
        result.update(threads=threads,batch=batch,measurementConfirmed=record['measurementConfirmed'],
            warmupSeconds=record.get('warmupSeconds'),stopReason=stop.get('reason'),drained=drained,
            auditPassed=checks['valid'],acceptedFinalized=finalized,jmeterExit=process.returncode,
            inventoryNotificationsDrained=inventory_drained,
            accountBudgetExhausted=stop.get('reason')=='http_account_budget_exhausted',
            resourceSafe=not monitor.gate.no_escalation,resources=dict(
                cpuPeak=max((s['cpu'] for s in monitor.samples),default=None),
                freeGiBMin=min((s['freeGiB'] for s in monitor.samples),default=None),
                pendingPeak=max((s['redisPending'] for s in monitor.samples),default=None),
                mqReadyPeak=max((s['mq']['ready'] for s in monitor.samples),default=None)))
        result['validStableWindow']=valid_window(result,record['measurementConfirmed'],stop.get('reason'),
            drained['complete'],checks['valid'],monitor.gate.no_escalation,finalized) and process.returncode==0
        if not record['measurementConfirmed'] and posts:
            observed_end=stop.get('timeMs') or max(int(p['httpCompletedMs']) for p in posts)+1
            if observed_end>start_load:
                result['interruptedObservation']=http_summary(posts,start_load,observed_end,stop.get('timeMs'))
                result['interruptedObservation']['validStableWindow']=False
        if selftest:
            result['selftestPassed']=bool(len(posts)==5 and stop.get('reason')=='selftest_post_limit'
                and not result['postsAfterStop'] and not result['allPhaseFailures'] and finalized
                and checks['valid'] and drained['complete'] and process.returncode==0)
        base.save(directory/'final-audit.json',checks)
        base.save(directory/'summary.json',result)
        print(json.dumps({k:result[k] for k in ('threads','httpQps','acceptedHttpP99Ms','httpCodes','stopReason','validStableWindow')},ensure_ascii=False),flush=True)
        return result
    finally:
        if process is not None and process.poll() is None:
            monitor.trip('http_runner_aborted')
            try: process.wait(timeout=20)
            except subprocess.TimeoutExpired: print('保留尚在运行的隔离进程与现场',flush=True)
        monitor.close()
        if output: output.close()
        # 完成采样后移除会话Cookie/CSRF；原始JTL与资源记录保留供本次验收。
        if process is None or process.poll() is not None:
            backend.http_qps_sender_active=False
            (directory/'sessions.json').unlink(missing_ok=True)


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--runtime',type=Path,required=True)
    p.add_argument('--java',required=True);p.add_argument('--jmeter-jar',required=True)
    p.add_argument('--tiers',default='1,2,5,10,25,50,100,200')
    p.add_argument('--warmup-seconds',type=int,default=30);p.add_argument('--seconds',type=int,default=30)
    p.add_argument('--accounts-per-tier',type=int,default=3990)
    p.add_argument('--reuse-accounts',action='store_true',help='按用户授权复用有效且仍有购票额度的旧账号，同一轮每人只发一笔')
    args=p.parse_args();args.runtime=args.runtime.resolve()
    args.reused_account_indices=set()
    levels=[int(n) for n in args.tiers.split(',')]
    if levels!=sorted(set(levels)) or not levels or min(levels)<1 or max(levels)>500: p.error('线程档位须升序且在1至500')
    if args.warmup_seconds<30 or args.seconds<30 or args.accounts_per_tier<100: p.error('预热/正式至少30秒，账号预算至少100')
    if not args.runtime.is_relative_to((base.ROOT/'.local/jmeter').resolve()): p.error('只接受.local/jmeter隔离运行目录')
    env,evidence=base.docker_guard(args.runtime)
    metadata=json.loads((args.runtime/'prepared-data.json').read_text(encoding='utf-8'))
    if metadata.get('bucketCount')!=8: raise RuntimeError('专项HTTP测试必须使用8桶SKU')
    for port in (18080,18081):
        with socket.socket() as probe:
            if probe.connect_ex(('127.0.0.1',port))==0: raise RuntimeError('隔离后端端口已占用')
    native=base.WindowsResources();native.host();time.sleep(1)
    if base.SafetyGate().update(native.host()): raise RuntimeError('主机资源不适合启动测试')
    directory=args.runtime.parent/('http-qps-'+secrets.token_hex(4));directory.mkdir()
    print('HTTP专项结果目录 '+str(directory),flush=True)
    base.save(directory/'source.json',dict(gitHead=base.command(['git','rev-parse','HEAD']),docker=evidence,
        scope='POST /api/v1/orders HTTP QPS/P99 only'))
    backend,logfile=base.start_backend(args.runtime,directory,args.java,12,16,capacity=1000)
    results=[]
    try:
        check=tier(args,directory/'selftest',backend,env,metadata,1,True)
        if not check.get('selftestPassed'): raise RuntimeError('最小真实样本自检未通过，停止后续负载')
        for threads in levels:
            cursor=json.loads((args.runtime/'next-account.json').read_text(encoding='utf-8'))
            with (args.runtime/'measure-users.csv').open(encoding='utf-8-sig') as stream: total=sum(1 for _ in stream)-1
            if not args.reuse_accounts and total-cursor<args.accounts_per_tier:
                print('未领取账号不足下一档预算，停止升档',flush=True);break
            result=tier(args,directory/f'threads-{threads}',backend,env,metadata,threads)
            results.append(result)
            if not result['validStableWindow']: break
        valid=[r for r in results if r['validStableWindow']]
        base.save(directory/'summary.json',dict(tiers=results,
            highestVerified=max(valid,key=lambda r:r['httpQps']) if valid else None))
    finally:
        if (not getattr(backend,'http_qps_sender_active',False) and base.drain(env,15)['complete']
                and inventory_notifications_drained(env)):
            if not base.stop_backend(backend,logfile,directory): raise RuntimeError('隔离后端正常关闭失败')
        else: print('在途尚未排空，保留隔离后端继续恢复',flush=True)


if __name__=='__main__': main()
