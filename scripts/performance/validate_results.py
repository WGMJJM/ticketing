"""交付前检查全部原始统计、数据库审计和敏感数据边界。"""
import json
import re
from perf import RESULTS, save

CASES = [(label,f'{scenario}-{vus}') for label in ['baseline','optimized']
         for scenario in ['query','hot-ample','hot-scarce','mixed','waitlist','checkin'] for vus in [10,25,50,100]]
CASES += [('pool20','query-100'),('batch100','mixed-50'),
          ('metrics-baseline','waitlist-100'),('metrics-optimized','waitlist-100')]


def main():
    result = []
    for label, name in CASES:
        folder = RESULTS/label/name
        meta = json.loads((folder/'environment.json').read_text(encoding='utf-8'))
        summary = json.loads((folder/'k6-summary.json').read_text(encoding='utf-8'))
        assert 'setup_data' not in summary, 'k6初始化数据不应进入报告'
        assert meta['k6ExitCode']==0 and not meta['monitorFailures'] and meta['audit']['passed'], '运行或审计未通过'
        metrics = summary['metrics']
        for key in ['server_errors','transport_errors']:
            assert metrics[key]['values']['count']==0, '存在系统或连接错误'
        assert metrics['unexpected_error']['values']['rate']==0 and metrics['http_req_failed']['values']['rate']==0
        assert metrics['steady_requests']['values']['count']>0
        if meta['scenario']=='checkin': assert metrics['checked_tickets']['values']['count']==1
        if meta['scenario']=='waitlist': assert meta['audit']['counts']['payment_record']==meta['vus']
        result.append({'label':label,'case':name,'passed':True})
    # 所有共享JSON都禁止凭证明文与敏感属性。SQL列名/归一化模式不包含实际密码值。
    for path in RESULTS.rglob('*.json'):
        text = path.read_text(encoding='utf-8')
        assert not re.search(r'TKT1\.[0-9]+\.[A-Za-z0-9_-]+\.',text), '发现完整电子票凭证'
        assert not re.search(r'"(?:password|token|credential|setup_data)"\s*:',text), '发现敏感属性'
    save(RESULTS/'validation.json',{'passed':True,'loadRuns':len(result),'cases':result})
    print(f'{len(result)}组原始压测、审计及报告敏感数据检查通过')


if __name__=='__main__':main()
