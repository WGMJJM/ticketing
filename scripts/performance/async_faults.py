"""第十四阶段真实进程、代理和数据库故障；仅允许既有固定ticketing-perf随机隔离环境。"""
import argparse
import json
from pathlib import Path
import secrets
import time
import uuid
from datetime import datetime,timezone,timedelta
import requests
import perf


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar',required=True)
    parser.add_argument('--label',default='stage14')
    args=parser.parse_args()
    import re
    if not re.fullmatch(r'[a-zA-Z0-9_-]{1,50}',args.label):raise RuntimeError('标签格式错误')
    report_path=perf.ROOT/'docs/architecture/results'/('async-faults-'+args.label+'.json')
    if report_path.exists():raise RuntimeError('已有结果必须保留，请使用新标签')
    perf.connect().close()
    report={'environment':'ticketing-perf-only','passed':False,'checks':[],'startedAt':datetime.now(timezone.utc).isoformat()}
    account={'username':'async_fault_'+uuid.uuid4().hex[:16],'password':secrets.token_urlsafe(24)}
    created=[]

    def restart(**overrides):
        perf.app_stop()
        perf.app_start(args.jar,rate_limit_mode='regression',PURCHASE_CAPACITY='3',PURCHASE_QUEUE_SECONDS='30',
                       PURCHASE_LEASE_SECONDS='2',PURCHASE_RECOVERY_SECONDS='1',
                       CATALOG_CACHE_ENABLED='true',PURCHASE_ADMISSION_LIMIT_ENABLED='true',**overrides)

    def check(name,condition,**details):
        if not condition:raise RuntimeError('故障验收未通过 '+name)
        report['checks'].append({'name':name,'passed':True,**details})
        perf.save(report_path,report)
        print('通过 '+name,flush=True)

    def target():
        admin=perf.admin();now=datetime.now(timezone.utc)
        activity=admin.request('POST','/api/v1/admin/activities',{'title':'演示 异步故障验收 '+uuid.uuid4().hex[:8],
          'category':'CONCERT','city':'上海','venue':'隔离验收场馆','summary':'专用环境演示','description':'仅用于本机隔离测试，无真实演出'})
        admin.request('POST',f'/api/v1/admin/activities/{activity["id"]}/publish',{'version':activity['version']})
        session=admin.request('POST',f'/api/v1/admin/activities/{activity["id"]}/sessions',{'title':'故障验收场次',
          'saleStartsAt':(now-timedelta(hours=1)).isoformat(),'startsAt':(now+timedelta(days=1)).isoformat(),
          'endsAt':(now+timedelta(days=1,hours=2)).isoformat()})
        sku=admin.request('POST',f'/api/v1/admin/sessions/{session["id"]}/tickets',{'name':'故障演示票','priceCents':'100',
          'totalStock':100,'saleStartsAt':(now-timedelta(minutes=30)).isoformat(),'saleEndsAt':(now+timedelta(hours=2)).isoformat(),'maxQuantityPerOrder':4})
        admin.request('POST',f'/api/v1/admin/tickets/{sku["id"]}/publish',{'version':sku['version']})
        return sku['id']

    def submit(client,sku,key):
        return client.request('POST','/api/v1/orders',{'skuId':sku,'quantity':1},{'Idempotency-Key':key},statuses=(200,202))

    def status(id):return perf.rows('SELECT status,order_id,processing_token FROM purchase_request WHERE id=%s',(id,))[0]
    def until(action,seconds=45):
        end=time.monotonic()+seconds
        while time.monotonic()<end:
            value=action()
            if value:return value
            time.sleep(0.2)
        raise RuntimeError('故障恢复超出观察窗口')

    try:
        restart()
        client=perf.Client(account,register=True)
        sku=target()
        restart(PURCHASE_MESSAGING_ENABLED='false',OUTBOX_JOBS_ENABLED='false')
        client=perf.Client(account)
        accepted=[submit(client,sku,'restart-'+str(i)) for i in range(3)]
        response=client.session.post(perf.BASE+'/api/v1/orders',json={'skuId':sku,'quantity':1},
                                     headers={**client.token,'Idempotency-Key':'full'},timeout=10)
        check('消费者与发布器停止时可靠受理有界',response.status_code==503 and response.json()['code']=='PURCHASE_QUEUE_FULL'
          and perf.rows('SELECT pending_count n FROM purchase_capacity WHERE id=1')[0]['n']==3
          and perf.stock_snapshot(sku)[0]['locked_stock']==0,accepted=3,rejected503=1)
        restart()
        client=perf.Client(account)
        for item in accepted:created.append(client.resolve_purchase(item)['id'])
        check('受理提交后强制停止应用仍恢复三笔订单',len(created)==3 and perf.rows('SELECT pending_count n FROM purchase_capacity WHERE id=1')[0]['n']==0)
        for id in created:client.request('POST',f'/api/v1/orders/{id}/cancel')
        created.clear()

        # 真实用户行锁阻塞消费者交易，观察PROCESSING后终止应用进程，模拟中途崩溃。
        sku=target();client=perf.Client(account)
        user=perf.rows('SELECT id FROM user_account WHERE username=%s',(account['username'],))[0]['id']
        restart(OUTBOX_JOBS_ENABLED='false')
        client=perf.Client(account);item=submit(client,sku,'processing-crash')
        with perf.connect() as lock_db,lock_db.cursor() as cur:
            cur.execute('START TRANSACTION');cur.execute('SELECT id FROM user_account WHERE id=%s FOR UPDATE',(user,))
            # 单独启用发布器需要重启会释放Session；改用同一已提交Outbox的真实命令发送。
            cfg=perf.config()
            event=perf.rows('SELECT event_id,payload FROM outbox_event WHERE event_id=%s',('purchase-'+item['requestId'],))[0]
            published=requests.post('http://127.0.0.1:25673/api/exchanges/%2F/ticketing.purchase/publish',
                auth=('ticketing_perf',cfg['mqPassword']),json={'properties':{'delivery_mode':2,'message_id':event['event_id'],
                    'content_type':'application/json'},'routing_key':'purchase.accepted','payload':event['payload'],'payload_encoding':'string'},timeout=10)
            check('中途崩溃前命令真实进入代理',published.status_code==200 and published.json().get('routed') is True)
            current=until(lambda:status(item['requestId']) if status(item['requestId'])['status']=='PROCESSING' else None)
            token=current['processing_token'];perf.app_stop();lock_db.commit()
        restart();client=perf.Client(account);created.append(client.resolve_purchase(item)['id'])
        check('处理中崩溃由租约与重启扫描恢复且不重复建单',status(item['requestId'])['processing_token']>token
          and len(perf.rows('SELECT id FROM ticket_order WHERE user_id=%s AND idempotency_key=%s',(user,'processing-crash')))==1)
        for id in created:client.request('POST',f'/api/v1/orders/{id}/cancel')
        created.clear()

        sku=target();perf.compose('stop','rabbitmq')
        client=perf.Client(account)
        unavailable=[submit(client,sku,'mq-outage-'+str(i)) for i in range(3)]
        end=time.monotonic()
        until(lambda:all(status(v['requestId'])['status']=='EXPIRED' for v in unavailable),seconds=45)
        check('代理持续停机后请求明确过期并释放预算',perf.rows('SELECT pending_count n FROM purchase_capacity WHERE id=1')[0]['n']==0,
              observedSeconds=round(time.monotonic()-end,2),expired=3)
        check('过期购票命令不再积压为待发事件',not perf.rows("SELECT id FROM outbox_event WHERE event_type='PURCHASE_REQUEST_ACCEPTED' AND status IN('PENDING','PROCESSING')"))
        next_request=submit(client,sku,'after-expiry')
        perf.compose('up','-d','--pull','never','--wait','rabbitmq')
        created.append(client.resolve_purchase(next_request)['id'])
        check('预算释放后再次受理且代理恢复后成功',len(created)==1)
        for id in created:client.request('POST',f'/api/v1/orders/{id}/cancel')
        created.clear()

        sku=target();client=perf.Client(account);key='db-outage'
        perf.compose('stop','mysql')
        observed=None
        try:
            result=client.session.post(perf.BASE+'/api/v1/orders',json={'skuId':sku,'quantity':1},
                       headers={**client.token,'Idempotency-Key':key},timeout=3)
            observed=str(result.status_code)
            check('数据库故障不返回受理成功',result.status_code not in (200,202))
        except requests.RequestException as error:observed=type(error).__name__
        perf.compose('up','-d','--pull','never','--wait','mysql')
        until(lambda:requests.get(perf.BASE+'/api/v1/auth/csrf',timeout=3).status_code==200)
        client=perf.Client(account)
        item=submit(client,sku,key);replay=submit(client,sku,key)
        created.append(client.resolve_purchase(item)['id'])
        check('数据库恢复后同键重试只产生一个请求和订单',item['requestId']==replay['requestId'] and
              len(perf.rows('SELECT id FROM ticket_order WHERE user_id=%s AND idempotency_key=%s',(user,key)))==1,
              faultResponse=observed)
        for id in created:client.request('POST',f'/api/v1/orders/{id}/cancel')
        until(lambda:not perf.rows("SELECT id FROM outbox_event WHERE status IN('PENDING','PROCESSING') LIMIT 1"))
        report['audit']=perf.audit();report['passed']=True
    finally:
        # 仅恢复已核验专用服务，不修复DDL、不清理失败数据。异常报告与私有日志保留。
        perf.compose('up','-d','--pull','never','--wait','mysql','rabbitmq')
        restart()
        report['finishedAt']=datetime.now(timezone.utc).isoformat();perf.save(report_path,report)
    print('异步真实故障验证完成',flush=True)


if __name__=='__main__':main()
