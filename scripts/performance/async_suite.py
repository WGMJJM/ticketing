"""第十四阶段固定六轮异步购票测量，保留原始结果和历史基线。"""
import argparse
from pathlib import Path
from types import SimpleNamespace
import json
import re
import perf


def report(label):
    directory=perf.RESULTS/label
    rows=[]
    for path in sorted(directory.glob('*/environment.json')):
        environment=json.loads(path.read_text(encoding='utf-8'))
        metrics=json.loads((path.parent/'k6-summary.json').read_text(encoding='utf-8'))['metrics']
        samples=json.loads((path.parent/'monitoring.json').read_text(encoding='utf-8'))
        seconds=environment['steadySeconds']
        def count(name):return metrics.get(name,{}).get('values',{}).get('count',0)
        def latency(name):
            values=metrics.get(name,{}).get('values',{})
            return {key:values.get(key) for key in ('med','p(95)','p(99)')}
        backlog=max((sum(v['n'] for v in point['outbox'] if v['status'] in ('PENDING','PROCESSING')) for point in samples),default=0)
        queue=max((int(q.get('messages_ready') or 0)+int(q.get('messages_unacknowledged') or 0)
            for point in samples for q in point.get('rabbitmq',[]) if q.get('name')=='ticketing.purchase.orders'),default=0)
        rows.append({'scenario':environment['scenario'],'vus':environment['vus'],
          'httpRps':count('steady_requests')/seconds,'acceptedTps':count('accepted_purchase_requests')/seconds,
          'orderCompletionTps':count('created_orders')/seconds,'acceptanceMs':latency('steady_acceptance_latency'),
          'completionMs':latency('steady_purchase_completion_latency'),'businessFailures':count('purchase_business_failures'),
          'backpressure503':count('admission_rejected'),'serverErrors':count('server_errors'),'transportErrors':count('transport_errors'),
          'outboxSampledPeak':backlog,'purchaseQueueSampledPeak':queue,
          'rowLockWaitMs':environment['mysqlDelta']['Innodb_row_lock_time'],
          'finalOutbox':environment['final']['outbox'],'finalPurchaseRequests':environment['final']['purchaseRequests'],
          'finalChannelLag':environment['final'].get('outboxChannelLag'),
          'auditPassed':environment['audit']['passed'],'k6ExitCode':environment['k6ExitCode']})
    if len(rows)!=6:raise RuntimeError('六轮结果未齐全，禁止标记套件完成')
    result={'environment':'ticketing-perf-only','protocol':'async-purchase-v1','rounds':rows,
      'measurement':'按稳定窗口内HTTP完成/终态查询完成统计；受理延迟与端到端延迟分离；不和历史同步订单TPS直接比较',
      'passed':all(v['auditPassed'] and v['k6ExitCode']==0 and v['serverErrors']==0 and v['transportErrors']==0 for v in rows)}
    perf.save(directory/'comparison.json',result)
    return result


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar',required=True)
    parser.add_argument('--label',default='stage14')
    parser.add_argument('--report-only',action='store_true')
    args=parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,63}',args.label):raise RuntimeError('标签格式不正确')
    if args.report_only:
        report(args.label);return
    directory=perf.RESULTS/args.label
    if directory.exists():raise RuntimeError('结果目录已存在，请保留并使用新标签')
    perf.connect().close()
    perf.app_stop();perf.app_start(args.jar,rate_limit_mode='regression')
    original=perf.RESULTS
    try:
        perf.RESULTS=directory;perf.environment_snapshot()
    finally:perf.RESULTS=original
    for mode,vus in [('hot-ample',10),('hot-ample',25),('hot-ample',50),('hot-ample',100),('hot-scarce',100),('mixed',25)]:
        print('开始',mode,vus,flush=True)
        perf.prepare(mode,vus)
        perf.run(SimpleNamespace(label=args.label,scenario=mode,vus=vus,warmup=10,duration=20,k6=None,
                                activity_id=None,session_id=None,sku_id=None))
    result=report(args.label)
    if not result['passed']:raise RuntimeError('套件未通过')
    print('六轮异步测量及审计通过',flush=True)


if __name__=='__main__':main()
