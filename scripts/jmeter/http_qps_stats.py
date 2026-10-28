"""按完整HTTP响应完成时间统计购票QPS；不计算订单吞吐。"""
from collections import Counter
from math import ceil


def http_summary(posts, start_ms, end_ms, stop_ms=None):
    if end_ms <= start_ms:
        raise ValueError('测量窗口必须为正数')
    window = [p for p in posts if start_ms <= int(p['httpCompletedMs']) < end_ms]
    responses = [p for p in window if p['responseCode'].isdigit()]
    accepted = [p for p in responses if p['responseCode'] == '202']
    times = sorted(float(p['postElapsedMs']) for p in accepted)
    keys = [p['idempotencyKey'] for p in posts]
    accounts = [p['accountIndex'] for p in posts]
    return dict(windowStartMs=start_ms, windowEndMs=end_ms, seconds=(end_ms-start_ms)/1000,
                httpResponses=len(responses), httpQps=len(responses)/((end_ms-start_ms)/1000),
                accepted202=len(accepted), accepted202Percent=100*len(accepted)/len(responses) if responses else 0,
                acceptedHttpP99Ms=times[ceil(len(times)*.99)-1] if times else None,
                httpCodes=dict(Counter(p['responseCode'] for p in responses)),
                networkFailures=sum(not p['responseCode'].isdigit() for p in window),
                allPhaseFailures=sum(p['responseCode'] != '202' or not p.get('requestId','').isdigit() for p in posts),
                duplicateKeys=len(keys)-len(set(keys)), duplicateAccounts=len(accounts)-len(set(accounts)),
                postsAfterStop=sum(int(p['postStartMs']) > stop_ms for p in posts) if stop_ms else 0,
                postsAfterDeadline=sum(int(p['postStartMs']) >= end_ms for p in posts))


def http_stability(posts, samples, now_ms):
    rates = [sum(left <= int(p['httpCompletedMs']) < left+5000 and p['responseCode']=='202'
                 for p in posts)/5 for left in range(now_ms-15000,now_ms,5000)]
    average = sum(rates)/3
    pressure = [s for s in samples if now_ms-15000 <= s['timeMs'] <= now_ms]
    queue_ok = len(pressure)>=5 and pressure[-1]['redisPending'] <= pressure[0]['redisPending']+10
    return dict(httpRates=rates, queueNotGrowing=queue_ok,
                stable=average>0 and max(rates)-min(rates)<=average*.2 and queue_ok)


def valid_window(summary, confirmed, stop_reason, drained, audit, no_escalation, finalized):
    return bool(confirmed and summary['httpResponses'] > 0 and not stop_reason and drained and audit
                and finalized and not no_escalation and not summary['allPhaseFailures']
                and not summary['networkFailures'] and not summary['duplicateKeys']
                and not summary['duplicateAccounts'] and not summary['postsAfterStop']
                and not summary['postsAfterDeadline'])
