#!/usr/bin/env python3
"""为隔离压测追加独立账号，不改已有业务数据及票档库存。"""
import argparse
import csv
import importlib.util
import secrets
from pathlib import Path
import pymysql

spec=importlib.util.spec_from_file_location('prepare_sessions',Path(__file__).with_name('prepare-sessions.py'))
sessions=importlib.util.module_from_spec(spec);spec.loader.exec_module(sessions)
parser=argparse.ArgumentParser();parser.add_argument('--count',type=int,required=True);parser.add_argument('--output',type=Path,required=True);args=parser.parse_args()
if not 1<=args.count<=10000 or not args.output.resolve().is_relative_to(sessions.RUNTIME) or args.output.exists():
    parser.error('账号数或隔离输出路径无效')
sessions.check_isolation();env=sessions.data.read_env(sessions.RUNTIME/'compose.env')
c=pymysql.connect(host='127.0.0.1',port=13307,user='root',password=env['MYSQL_ROOT_PASSWORD'],database='ticketingjmeter',autocommit=False)
try:
 with c.cursor() as cur:
  cur.execute('SELECT COUNT(*) FROM user_account'); current=cur.fetchone()[0]
  if current-1+args.count>10000:raise RuntimeError('隔离买家总数不能超过 10000')
  cur.execute('SELECT password_hash,status,role FROM user_account WHERE username=%s',(env['ADMIN_BOOTSTRAP_USERNAME'],));seed=cur.fetchone()
  if not seed or seed[1:]!=('ACTIVE','ADMIN'):raise RuntimeError('隔离种子账号无效')
  batch=secrets.token_hex(5);password=env['ADMIN_BOOTSTRAP_PASSWORD']
  users=[(f'jm_extra_{batch}_{i:04d}',password) for i in range(1,args.count+1)]
  cur.executemany("""INSERT INTO user_account(username,email,email_verified_at,password_hash,status,role,created_at,updated_at)
    VALUES(%s,%s,UTC_TIMESTAMP(6),%s,'ACTIVE','USER',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))""",
    [(username,f'{username}@example.invalid',seed[0]) for username,_ in users])
 c.commit()
 with args.output.open('x',encoding='utf8',newline='') as f:
  writer=csv.writer(f);writer.writerow(['username','password']);writer.writerows(users)
 print(f'已追加 {len(users)} 个独立隔离买家；现有票档库存未修改。')
except Exception:
 c.rollback();raise
finally:c.close()
