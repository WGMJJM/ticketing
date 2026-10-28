#!/usr/bin/env python3
"""只读观察隔离 Redis 和 RabbitMQ，有限等待购票队列与恢复索引排空。"""
import argparse
import base64
import importlib.util
import json
import subprocess
import time
import urllib.request
from pathlib import Path

spec = importlib.util.spec_from_file_location('prepare_sessions', Path(__file__).with_name('prepare-sessions.py'))
sessions = importlib.util.module_from_spec(spec); spec.loader.exec_module(sessions)
parser = argparse.ArgumentParser()
parser.add_argument('--output', type=Path, required=True)
parser.add_argument('--window-end-ms', type=int, required=True)
parser.add_argument('--timeout-seconds', type=int, default=150)
args = parser.parse_args()
if not args.output.resolve().is_relative_to(sessions.RUNTIME) or not 1 <= args.timeout_seconds <= 300:
    parser.error('只允许隔离输出和有界等待')
sessions.check_isolation()
env = sessions.data.read_env(sessions.RUNTIME/'compose.env')
auth = base64.b64encode((env['RABBITMQ_USER']+':'+env['RABBITMQ_PASSWORD']).encode()).decode()
def queue(name):
    req = urllib.request.Request('http://127.0.0.1:15674/api/queues/%2F/'+name, headers={'Authorization':'Basic '+auth})
    with urllib.request.urlopen(req, timeout=3) as r:
        item=json.load(r)
    return {'ready':item['messages_ready'], 'unacked':item['messages_unacknowledged'], 'consumers':item['consumers']}
def redis(*command):
    result = subprocess.run(['docker','exec','ticketing-jmeter-redis-1','redis-cli','--raw',*command], check=True, capture_output=True, text=True, timeout=5)
    return result.stdout.strip()
deadline = time.monotonic() + args.timeout_seconds
history = []
while True:
    sample = {'timeMs':int(time.time()*1000), 'queue':queue('ticketing.purchase.orders'),
              'dead':queue('ticketing.purchase.orders.dead'),
              'pending':int(redis('GET','ticketing:seckill:v2:ticketingjmeter:pending') or 0),
              'recovery':int(redis('ZCARD','ticketing:seckill:v2:ticketingjmeter:recovery') or 0),
              'skuAvailable':int(redis('HGET','ticketing:seckill:v2:ticketingjmeter:sku:1','available')),
              'skuPending':int(redis('HGET','ticketing:seckill:v2:ticketingjmeter:sku:1','pending') or 0)}
    history.append(sample)
    drained = not any((sample['queue']['ready'],sample['queue']['unacked'],sample['dead']['ready'],sample['dead']['unacked'],sample['pending'],sample['recovery'],sample['skuPending']))
    if drained or time.monotonic() >= deadline:
        break
    time.sleep(3)
result={'drained':drained,'drainSecondsAfterWindow':max(0,(sample['timeMs']-args.window_end_ms)/1000),'samples':history}
args.output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
print(json.dumps({'drained':drained,'drainSecondsAfterWindow':result['drainSecondsAfterWindow'],'last':sample},ensure_ascii=False))
if not drained:
    raise SystemExit(2)
