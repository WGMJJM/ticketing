#!/usr/bin/env python3
"""发压前核验隔离数据库、独立会话数量、单 SKU 库存和候补门禁。"""
import argparse
import csv
import importlib.util
import json
import math
import subprocess
from pathlib import Path

import pymysql

spec = importlib.util.spec_from_file_location('prepare_sessions', Path(__file__).with_name('prepare-sessions.py'))
sessions = importlib.util.module_from_spec(spec); spec.loader.exec_module(sessions)
parser = argparse.ArgumentParser()
parser.add_argument('--sessions', type=Path, required=True)
parser.add_argument('--threads', type=int, required=True)
parser.add_argument('--rate', type=float, required=True)
parser.add_argument('--seconds', type=int, required=True)
args = parser.parse_args()
if not args.sessions.resolve().is_relative_to(sessions.RUNTIME) or min(args.threads, args.rate, args.seconds) <= 0:
    parser.error('需要隔离会话文件和正数负载')
sessions.check_isolation()
with args.sessions.open(encoding='utf8', newline='') as f:
    rows = list(csv.DictReader(f))
user_ids = [r.get('userId', '') for r in rows]
if any(not x.isdigit() for x in user_ids) or len(set(user_ids)) != len(rows):
    raise SystemExit('测试会话缺少有效且唯一的真实用户身份')
env = sessions.data.read_env(sessions.RUNTIME/'compose.env')
with pymysql.connect(host='127.0.0.1', port=13307, user=env['MYSQL_USER'], password=env['MYSQL_PASSWORD'], database='ticketingjmeter') as c:
    with c.cursor() as cur:
        cur.execute('SELECT total_stock,locked_stock,sold_stock FROM ticket_sku WHERE id=1')
        total, locked, sold = cur.fetchone()
        cur.execute("SELECT COUNT(*) FROM waitlist_entry WHERE sku_id=1 AND status IN ('WAITING','PROCESSING')")
        waitlist = cur.fetchone()[0]
        eligible = 0
        for offset in range(0, len(user_ids), 500):
            chunk = user_ids[offset:offset+500]
            marks = ','.join(['%s'] * len(chunk))
            cur.execute(f"""SELECT COUNT(*) FROM user_account u
                LEFT JOIN user_session_quota q ON q.user_id=u.id AND q.session_id=1
                WHERE u.id IN ({marks}) AND u.status='ACTIVE' AND u.role='USER'
                  AND COALESCE(q.occupied_quantity,0)=0
                  AND NOT EXISTS (SELECT 1 FROM purchase_request p WHERE p.user_id=u.id AND p.sku_id=1)""", chunk)
            eligible += cur.fetchone()[0]
pending = subprocess.run(['docker','exec','ticketing-jmeter-redis-1','redis-cli','--raw','GET',
                          'ticketing:seckill:v2:ticketingjmeter:pending'],check=True,capture_output=True,text=True).stdout.strip()
required = math.ceil(args.rate * args.seconds) + args.threads
available = total - locked - sold
result = {'sessions': len(rows), 'validDistinctSessions': len({r['cookie'] for r in rows}), 'targetPosts': math.ceil(args.rate * args.seconds),
          'reservedSessionsForThreads': args.threads, 'requiredSessions': required, 'skuAvailableStock': available,
          'eligibleFreshUsers': eligible, 'maximumOneOrderPerAccount': eligible, 'waitingWaitlist': waitlist,
          'pendingBeforeStage': int(pending or 0)}
print(json.dumps(result, ensure_ascii=False))
if len(rows) < required or result['validDistinctSessions'] != len(rows) or eligible != len(rows) or available < result['targetPosts'] or waitlist or result['pendingBeforeStage']:
    raise SystemExit('独立账号或库存不足，或候补未排空；拒绝发压')
