#!/usr/bin/env python3
"""仅对独立压测库与专用 Redis/RabbitMQ 做最终只读一致性核验。"""
import argparse
import base64
import importlib.util
import json
import subprocess
import urllib.request
from pathlib import Path
import pymysql

spec=importlib.util.spec_from_file_location('prepare_sessions',Path(__file__).with_name('prepare-sessions.py'))
sessions=importlib.util.module_from_spec(spec);spec.loader.exec_module(sessions)
parser=argparse.ArgumentParser();parser.add_argument('--output',required=True,type=Path);args=parser.parse_args()
if not args.output.resolve().is_relative_to(sessions.RUNTIME): parser.error('输出必须位于隔离目录')
sessions.check_isolation();env=sessions.data.read_env(sessions.RUNTIME/'compose.env')
c=pymysql.connect(host='127.0.0.1',port=13307,user=env['MYSQL_USER'],password=env['MYSQL_PASSWORD'],database='ticketingjmeter',cursorclass=pymysql.cursors.DictCursor)
result={}
with c.cursor() as q:
 def query(name,sql):q.execute(sql);result[name]=q.fetchall()
 query('stock','SELECT total_stock,locked_stock,sold_stock FROM ticket_sku WHERE id=1')
 query('orderStates',"SELECT o.status,COUNT(*) orders,SUM(i.quantity) quantity FROM ticket_order o JOIN ticket_order_item i ON i.order_id=o.id WHERE i.sku_id=1 GROUP BY o.status")
 query('requestStates',"SELECT status,COUNT(*) count FROM purchase_request WHERE idempotency_operation='CREATE_ORDER' GROUP BY status")
 query('relationMismatch',"""SELECT COUNT(*) n FROM purchase_request p LEFT JOIN ticket_order o ON o.id=p.order_id
   LEFT JOIN ticket_order_item i ON i.order_id=o.id AND i.sku_id=p.sku_id WHERE p.idempotency_operation='CREATE_ORDER'
   AND ((p.status='SUCCEEDED' AND (o.id IS NULL OR o.user_id<>p.user_id OR o.idempotency_key<>p.idempotency_key
   OR o.idempotency_operation<>p.idempotency_operation OR i.id IS NULL OR i.quantity<>p.quantity))
   OR (p.status<>'SUCCEEDED' AND p.order_id IS NOT NULL))""")
 query('duplicateOrders',"""SELECT COUNT(*) n FROM (SELECT user_id,idempotency_operation,idempotency_key FROM ticket_order
   WHERE idempotency_operation='CREATE_ORDER' GROUP BY user_id,idempotency_operation,idempotency_key HAVING COUNT(*)>1) t""")
 query('duplicateOrderRequests',"""SELECT COUNT(*) n FROM (SELECT order_id FROM purchase_request WHERE order_id IS NOT NULL
   GROUP BY order_id HAVING COUNT(*)>1) t""")
 query('orderWithoutRequest',"""SELECT COUNT(*) n FROM ticket_order o LEFT JOIN purchase_request p ON p.order_id=o.id
   WHERE o.idempotency_operation='CREATE_ORDER' AND p.id IS NULL""")
 query('orderItemMismatch',"""SELECT COUNT(*) n FROM (SELECT o.id,COUNT(i.id) items,SUM(i.quantity) qty FROM ticket_order o
   LEFT JOIN ticket_order_item i ON i.order_id=o.id WHERE o.idempotency_operation='CREATE_ORDER'
   GROUP BY o.id HAVING items<>1 OR qty<>1) t""")
c.close()
root=pymysql.connect(host='127.0.0.1',port=13307,user='root',password=env['MYSQL_ROOT_PASSWORD'],database='ticketingjmeter',cursorclass=pymysql.cursors.DictCursor)
with root.cursor() as q:
 q.execute("SELECT ERROR_NAME,SUM_ERROR_RAISED,SUM_ERROR_HANDLED FROM performance_schema.events_errors_summary_global_by_error WHERE ERROR_NUMBER=1213")
 result['deadlockErrorCounters']=q.fetchall()
root.close()
def redis(*command):return subprocess.run(['docker','exec','ticketing-jmeter-redis-1','redis-cli','--raw',*command],check=True,capture_output=True,text=True).stdout.strip()
result['redis']={'available':int(redis('HGET','ticketing:seckill:v2:ticketingjmeter:sku:1','available')),
                 'skuPending':int(redis('HGET','ticketing:seckill:v2:ticketingjmeter:sku:1','pending') or 0),
                 'pending':int(redis('GET','ticketing:seckill:v2:ticketingjmeter:pending') or 0),
                 'recovery':int(redis('ZCARD','ticketing:seckill:v2:ticketingjmeter:recovery') or 0)}
auth=base64.b64encode((env['RABBITMQ_USER']+':'+env['RABBITMQ_PASSWORD']).encode()).decode()
for name in ('ticketing.purchase.orders','ticketing.purchase.orders.dead'):
 req=urllib.request.Request('http://127.0.0.1:15674/api/queues/%2F/'+name,headers={'Authorization':'Basic '+auth})
 with urllib.request.urlopen(req,timeout=5) as response:item=json.load(response)
 result[name]={'ready':item['messages_ready'],'unacked':item['messages_unacknowledged'],'consumers':item['consumers']}
stock=result['stock'][0];r=result['redis'];states={row['status']:int(row['quantity']) for row in result['orderStates']}
result['consistent']=(stock['total_stock']-stock['locked_stock']-stock['sold_stock']==r['available']
 and stock['locked_stock']==states.get('PENDING',0) and stock['sold_stock']==states.get('PAID',0)
 and not any((r['pending'],r['skuPending'],r['recovery'],result['ticketing.purchase.orders']['ready'],
 result['ticketing.purchase.orders']['unacked'],result['ticketing.purchase.orders.dead']['ready'],
 result['ticketing.purchase.orders.dead']['unacked']))
 and all(result[name][0]['n']==0 for name in ('relationMismatch','duplicateOrders','duplicateOrderRequests','orderWithoutRequest','orderItemMismatch')))
args.output.write_text(json.dumps(result,default=str,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
print(json.dumps(result,default=str,ensure_ascii=False,indent=2))
if not result['consistent']:raise SystemExit('一致性核验未通过')
