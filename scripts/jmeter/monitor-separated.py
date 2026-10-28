#!/usr/bin/env python3
"""隔离压测资源监控：约两秒采样，触发阈值时创建停止发单标记。"""
import base64, csv, ctypes, datetime, importlib.util, json, re, subprocess, sys, threading, time, urllib.request
from pathlib import Path
import pymysql
from safety_rules import SafetyRules

root=Path(__file__).resolve().parents[2]/'.local'/'jmeter'; out=Path(sys.argv[1]).resolve(); backend_pid=int(sys.argv[2])
if not out.is_relative_to(root.resolve()): raise RuntimeError('监控目录必须位于隔离目录')
spec=importlib.util.spec_from_file_location('prepare_sessions',Path(__file__).with_name('prepare-sessions.py'))
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.check_isolation()
env=module.data.read_env(root/'compose.env')
auth=base64.b64encode((env['RABBITMQ_USER']+':'+env['RABBITMQ_PASSWORD']).encode()).decode()
kernel=ctypes.windll.kernel32; psapi=ctypes.windll.psapi
kernel.OpenProcess.restype=ctypes.c_void_p
class FT(ctypes.Structure): _fields_=[('low',ctypes.c_uint32),('high',ctypes.c_uint32)]
class MEM(ctypes.Structure): _fields_=[('length',ctypes.c_ulong),('load',ctypes.c_ulong),('total',ctypes.c_ulonglong),('avail',ctypes.c_ulonglong),('totalpage',ctypes.c_ulonglong),('availpage',ctypes.c_ulonglong),('totalvirtual',ctypes.c_ulonglong),('availvirtual',ctypes.c_ulonglong),('extended',ctypes.c_ulonglong)]
class PMC(ctypes.Structure): _fields_=[('cb',ctypes.c_ulong),('faults',ctypes.c_ulong),('peak',ctypes.c_size_t),('working',ctypes.c_size_t),('peakpaged',ctypes.c_size_t),('paged',ctypes.c_size_t),('peaknonpaged',ctypes.c_size_t),('nonpaged',ctypes.c_size_t),('pagefile',ctypes.c_size_t),('peakpagefile',ctypes.c_size_t)]
def value(v): return (v.high<<32)|v.low
def system():
 a,b,c=FT(),FT(),FT();kernel.GetSystemTimes(ctypes.byref(a),ctypes.byref(b),ctypes.byref(c));return [value(x) for x in (a,b,c)]
def process(pid):
 if not pid:return None
 h=kernel.OpenProcess(0x1010,False,pid)
 if not h:return None
 try:
  create,exit,k,u=FT(),FT(),FT(),FT();m=PMC();m.cb=ctypes.sizeof(m)
  if not kernel.GetProcessTimes(ctypes.c_void_p(h),ctypes.byref(create),ctypes.byref(exit),ctypes.byref(k),ctypes.byref(u)):return None
  if not psapi.GetProcessMemoryInfo(ctypes.c_void_p(h),ctypes.byref(m),m.cb):return None
  return {'Id':pid,'CPU':(value(k)+value(u))/10000000,'WorkingSet64':m.working}
 finally:kernel.CloseHandle(ctypes.c_void_p(h))
def run(cmd,timeout=8):
 try:return subprocess.run(cmd,capture_output=True,text=True,timeout=timeout,check=True).stdout.strip()
 except Exception:return ''
def bytesize(text):
 match=re.match(r'([0-9.]+)\s*([A-Za-z]+)',text)
 if not match:return 0
 unit=match.group(2);factors={'B':1,'kB':1000,'MB':1000000,'GB':1000000000,'KiB':1024,'MiB':1024**2,'GiB':1024**3}
 return float(match.group(1))*factors.get(unit,0)
def connection():return pymysql.connect(host='127.0.0.1',port=13307,user='root',password=env['MYSQL_ROOT_PASSWORD'],database='ticketingjmeter',autocommit=True,cursorclass=pymysql.cursors.DictCursor,connect_timeout=2)
def digest(path):
 with connection() as conn:
  with conn.cursor() as c:
   c.execute("SELECT DIGEST_TEXT,COUNT_STAR,SUM_TIMER_WAIT,MAX_TIMER_WAIT,SUM_LOCK_TIME FROM performance_schema.events_statements_summary_by_digest WHERE SCHEMA_NAME='ticketingjmeter' AND (DIGEST_TEXT LIKE '%ticket_sku%' OR DIGEST_TEXT LIKE '%purchase_request%') ORDER BY SUM_TIMER_WAIT DESC LIMIT 40")
   path.write_text(json.dumps(c.fetchall(),default=str,indent=2),encoding='utf8')
shared={}; lock=threading.Lock(); done=threading.Event()
def update(**kw):
 with lock:shared.update(kw)
def queue_mysql():
 try: conn=connection()
 except Exception:conn=None
 while not done.is_set():
  began=time.monotonic()
  try:
   req=urllib.request.Request('http://127.0.0.1:15674/api/queues/%2F/ticketing.purchase.orders',headers={'Authorization':'Basic '+auth})
   with urllib.request.urlopen(req,timeout=1) as r:q=json.load(r)
   update(queue={k:q.get(k) for k in ('messages_ready','messages_unacknowledged','consumers','message_stats')},queueAt=time.time())
  except Exception:update(queueError='unavailable')
  try:
   if conn is None:conn=connection()
   with conn.cursor() as c:
    c.execute("SELECT UTC_TIMESTAMP(6) now, (SELECT COUNT(*) FROM performance_schema.data_lock_waits) lock_waits, (SELECT COUNT(*) FROM information_schema.innodb_trx) active_trx, (SELECT COUNT(*) FROM information_schema.processlist WHERE db='ticketingjmeter') db_connections")
    mysql=c.fetchone();c.execute("SHOW GLOBAL STATUS WHERE Variable_name IN ('Innodb_row_lock_waits','Innodb_row_lock_time','Threads_running')");status=c.fetchall()
   update(mysql=mysql,mysqlStatus=status)
  except Exception:
   if conn:
    try:conn.close()
    except Exception:pass
   conn=None;update(mysqlError='unavailable')
  done.wait(max(0,2-(time.monotonic()-began)))
 if conn:conn.close()
def docker_metrics():
 total_text=run(['docker','info','--format','{{.MemTotal}}']);total=int(total_text) if total_text.isdigit() else 0
 while not done.is_set():
  began=time.monotonic();raw=run(['docker','stats','--no-stream','--format','{{json .}}'],timeout=8)
  try:
   items=[json.loads(line) for line in raw.splitlines() if line.strip()]
   used=sum(bytesize(x['MemUsage'].split('/')[0].strip()) for x in items)
   if items and total:update(containers=raw,dockerUsedBytes=used,dockerTotalBytes=total,dockerAt=time.time())
  except Exception:update(dockerError='unavailable')
  done.wait(max(0,2-(time.monotonic()-began)))
def jvm_metrics():
 while not done.is_set():
  began=time.monotonic();names=run(['jcmd','-l']);jmeter=None
  for line in names.splitlines():
   if 'ApacheJMeter.jar' in line and line.split()[0].isdigit():jmeter=int(line.split()[0])
  dump=run(['jcmd',str(backend_pid),'Thread.print']); gc=run(['jstat','-gcutil',str(backend_pid)])
  data={'jmeterPid':jmeter,'javaList':names,'hikariGetConnectionFrames':dump.count('HikariPool.getConnection'),'hikariBagWaitFrames':dump.count('ConcurrentBag.borrow'),'backendGc':gc}
  if jmeter:data['jmeterGc']=run(['jstat','-gcutil',str(jmeter)])
  update(**data);done.wait(max(0,12-(time.monotonic()-began)))
def safety(reason):
 if not (out/'stop.flag').exists():
  (out/'stop.flag').write_text(reason,encoding='utf8')
  with (out/'safety-events.jsonl').open('a',encoding='utf8') as f:f.write(json.dumps({'utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'reason':reason})+'\n')
try:digest(out/'mysql-digest-before.json')
except Exception:update(digestError='unavailable')
for fn in (queue_mysql,docker_metrics,jvm_metrics):threading.Thread(target=fn,daemon=True).start()
prev=system();last=time.monotonic();rules=SafetyRules();headers=None;offset=0;pending=b'';overload=0
while not (out/'monitor-finished.flag').exists() and time.monotonic()-last<400:
 tick=time.monotonic();time.sleep(2);now=time.monotonic();cur=system();idle,k,u=[cur[i]-prev[i] for i in range(3)];prev=cur
 cpu=round(100*(1-idle/(k+u)),1) if k+u else 0;mem=MEM();mem.length=ctypes.sizeof(mem);kernel.GlobalMemoryStatusEx(ctypes.byref(mem))
 with lock: latest=dict(shared)
 row={'utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'mono':now,'sampleIntervalSeconds':round(now-tick,3),'hostCpuPct':cpu,'availableBytes':mem.avail,'memoryLoadPct':mem.load}
 row.update(latest); proc=[p for p in (process(backend_pid),process(latest.get('jmeterPid'))) if p];row['javaProcesses']=json.dumps(proc)
 q=latest.get('queue') or {}
 reason=rules.evaluate(now,cpu,mem.load,mem.avail,latest.get('dockerUsedBytes',0),latest.get('dockerTotalBytes',0),q.get('messages_ready') or 0,q.get('messages_unacknowledged') or 0)
 if reason:safety(reason)
 if now-last>15 and (not latest.get('dockerAt') or time.time()-latest['dockerAt']>15):safety('docker-monitor-unavailable')
 if now-last>15 and (not latest.get('queueAt') or time.time()-latest['queueAt']>15):safety('rabbit-monitor-unavailable')
 try:
  with (out/'measure.jtl').open('rb') as f:f.seek(offset);data=f.read();offset=f.tell()
  lines=(pending+data).split(b'\n');pending=lines.pop();batch=[]
  for raw in lines:
   fields=next(csv.reader([raw.decode('utf8').rstrip('\r')]))
   if headers is None:headers=fields;continue
   if len(fields)==len(headers):batch.append(dict(zip(headers,fields)))
  http=[x for x in batch if x.get('label')=='购票 POST'];errors=sum(x.get('responseCode','').startswith('Non HTTP') for x in http);rejected=sum(x.get('success','').lower()=='false' for x in http)
  row.update(clientHttpSamples=len(http),clientNetworkErrors=errors,clientRejected=rejected)
  overload=overload+1 if len(http)>=20 and rejected/len(http)>=.25 else 0
  if (len(http)>=20 and errors>=10 and errors/len(http)>=.1) or overload>=2:safety('sustained-http-errors')
 except FileNotFoundError:pass
 gcfile=out/'jmeter-gc.log'
 if gcfile.exists():
  gc=gcfile.read_text(encoding='utf8',errors='ignore')
  if any(float(x)>500 for x in re.findall(r'Pause Full[^\n]*?([0-9.]+)ms',gc)):safety('severe-full-gc')
 for file in (out/'jmeter.log',out.parent/'backend.log',out.parent/'backend.err'):
  if file.exists():
   with file.open('rb') as f:f.seek(max(0,file.stat().st_size-32768)); tail=f.read().decode('utf8',errors='ignore')
   if 'OutOfMemoryError' in tail:safety('out-of-memory')
 row['protectionStop']=(out/'stop.flag').exists()
 with (out/'resources.jsonl').open('a',encoding='utf8') as f:f.write(json.dumps(row,ensure_ascii=False,default=str)+'\n')
done.set()
try:digest(out/'mysql-digest-after.json')
except Exception:pass
