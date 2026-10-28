#!/usr/bin/env python3
"""按正式窗口汇总隔离环境低频资源采样；样本为快照而非连续指标。"""
import argparse
import datetime as dt
import json
import re
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('directory', type=Path)
parser.add_argument('--backend-pid', type=int, required=True)
args = parser.parse_args()
directory = args.directory
window = json.loads((directory/'window.json').read_text(encoding='utf-8-sig'))
rows = [json.loads(line) for line in (directory/'resources.jsonl').read_text(encoding='utf8').splitlines()]
def ms(row):
    return dt.datetime.fromisoformat(row['utc']).timestamp()*1000
selected = [r for r in rows if window['windowStartMs'] <= ms(r) < window['windowEndMs']]
queues = [r['queue'] for r in selected if 'queue' in r]
mysql = [r['mysql'] for r in selected if 'mysql' in r]
java_id = None
for r in rows:
    for line in r.get('javaList','').splitlines():
        if 'ApacheJMeter.jar' in line and line.split()[0].isdigit(): java_id = int(line.split()[0])
def processes(r):
    try:
        parsed = json.loads(r.get('javaProcesses','[]'))
        return parsed if isinstance(parsed,list) else [parsed]
    except (ValueError, TypeError):
        return []
def cpu_rate(pid):
    samples = [(ms(r), p) for r in selected for p in processes(r) if p.get('Id') == pid]
    if len(samples) < 2: return None
    (a,x),(b,y) = samples[0],samples[-1]
    return round((y['CPU']-x['CPU'])/((b-a)/1000),2) if b>a else None
def peak_memory(pid):
    return max((p['WorkingSet64'] for r in selected for p in processes(r) if p.get('Id')==pid),default=None)
def status(r, name):
    return next((int(x['Value']) for x in r.get('mysqlStatus',[]) if x['Variable_name']==name),None)
def status_delta(name):
    samples=[r for r in selected if status(r,name) is not None]
    return status(samples[-1],name)-status(samples[0],name) if len(samples)>1 else None
q_counts=[]
for r in selected:
    q=r.get('queue',{}); stats=q.get('message_stats') or {}
    if 'ack' in stats: q_counts.append((ms(r),stats.get('ack'),stats.get('publish')))
result={'samples':len(selected), 'hostCpuPctMin':min((r['hostCpuPct'] for r in selected),default=None),
        'hostCpuPctMax':max((r['hostCpuPct'] for r in selected),default=None),
        'minAvailableBytes':min((r['availableBytes'] for r in selected),default=None),
        'maxQueueReady':max((q.get('messages_ready',0) for q in queues),default=None),
        'maxQueueUnacked':max((q.get('messages_unacknowledged',0) for q in queues),default=None),
        'consumers':sorted({q.get('consumers') for q in queues if q.get('consumers') is not None}),
        'mysqlLockWaitSnapshotsMax':max((x['lock_waits'] for x in mysql),default=None),
        'mysqlRowLockWaitsDelta':status_delta('Innodb_row_lock_waits'), 'mysqlRowLockTimeMsDelta':status_delta('Innodb_row_lock_time'),
        'mysqlConnectionsMax':max((x['db_connections'] for x in mysql),default=None),
        'hikariBorrowStackMax':max((r.get('hikariBagWaitFrames',0) for r in selected),default=None),
        'backendCpuCoresApprox':cpu_rate(args.backend_pid), 'jmeterPid':java_id, 'jmeterCpuCoresApprox':cpu_rate(java_id),
        'backendPeakWorkingSet':peak_memory(args.backend_pid), 'jmeterPeakWorkingSet':peak_memory(java_id)}
if len(q_counts)>1:
    span=(q_counts[-1][0]-q_counts[0][0])/1000
    result['ackRateApprox']=(q_counts[-1][1]-q_counts[0][1])/span if span else None
    result['publishRateApprox']=(q_counts[-1][2]-q_counts[0][2])/span if span else None
for file in ('backend-gc.log','jmeter-gc.log'):
    path=directory/file
    if path.exists():
        text=path.read_text(encoding='utf8');result[file]={'fullGc':text.count('Pause Full'),'youngGc':text.count('Pause Young')}
(directory/'resource-summary.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
print(json.dumps(result,ensure_ascii=False))
