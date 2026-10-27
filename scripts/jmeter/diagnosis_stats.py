"""固定 [start,end) 窗口，区分POST受理、DB创建及窗口后排空；不推测客户端终态时刻。"""
from collections import Counter
from math import ceil


def quantiles(values):
    values = sorted(values)
    return {f'p{int(p * 100)}': values[ceil(len(values) * p) - 1] if values else None
            for p in (.5, .95, .99)}


def has_persistent_pending_growth(samples):
    """实时预扣积压持续增长；Rabbit管理统计的刷新跳变不能替代实时证据。"""
    return (len(samples) >= 5 and samples[-1]['redisPending'] > samples[0]['redisPending']+30
            and all(b['redisPending'] >= a['redisPending'] for a,b in zip(samples,samples[1:])))


def summarize(posts, orders, start_ms, end_ms):
    if end_ms <= start_ms:
        raise ValueError('固定窗口结束须晚于开始')
    window = [p for p in posts if start_ms <= int(p['postStartMs']) < end_ms]
    keys = [p['idempotencyKey'] for p in posts]
    counts = Counter(p['responseCode'] for p in window)
    accepted = [p for p in window if p['responseCode'] == '202' and p.get('requestId', '').isdigit()]
    accepted_keys = {p['idempotencyKey'] for p in accepted}
    by_key = {row['idempotency_key']: row for row in orders}
    incomplete, failed, delays = 0, Counter(), []
    for post in accepted:
        row = by_key.get(post['idempotencyKey'])
        if row is None or row['status'] == 'QUEUED':
            incomplete += 1
        elif row['status'] != 'SUCCEEDED':
            failed[row['status']] += 1
        elif row['order_id'] is None or str(row['id']) != post['requestId']:
            incomplete += 1
        else:
            delays.append(float(row['created_ms']) - float(row['accepted_ms']))
    success = {row['order_id']: row for row in orders
               if row['status'] == 'SUCCEEDED' and row['order_id'] is not None}
    in_window = [row for row in success.values() if start_ms <= float(row['created_ms']) < end_ms]
    seconds = (end_ms - start_ms) / 1000
    return dict(windowStartMs=start_ms, windowEndMs=end_ms, seconds=seconds,
                attempted=len(window), attemptedRps=len(window) / seconds, httpCounts=dict(counts),
                accepted=len(accepted), acceptedRps=len(accepted) / seconds,
                failureCodes=dict(Counter(p.get('failureCode', '') for p in window if p['responseCode'] != '202')),
                httpMs=quantiles(float(p['postElapsedMs']) for p in window),
                httpAcceptedMs=quantiles(float(p['postElapsedMs']) for p in accepted),
                arrivalLatenessMs=quantiles(max(0, int(p['postStartMs'])-int(p.get('scheduledMs', p['postStartMs']))) for p in window),
                orderCount=len(in_window), orderTps=len(in_window) / seconds,
                orderTpsBy5Seconds=[dict(startMs=left, endMs=min(left+5000,end_ms),
                    orders=sum(left <= float(row['created_ms']) < min(left+5000,end_ms) for row in in_window),
                    tps=sum(left <= float(row['created_ms']) < min(left+5000,end_ms) for row in in_window)
                        / ((min(left+5000,end_ms)-left)/1000)) for left in range(start_ms,end_ms,5000)],
                drainOrders=sum(float(row['created_ms']) >= end_ms for row in success.values()),
                beforeWindowOrders=sum(float(row['created_ms']) < start_ms for row in success.values()),
                duplicatePostKeys=len(keys) - len(set(keys)),
                acceptedMissingRequestId=sum(p['responseCode'] == '202' and not p.get('requestId', '').isdigit() for p in window),
                serverQueueAndCreateMs=quantiles(delays), successfulAcceptedSamples=len(delays),
                incompleteAccepted=incomplete, failedAccepted=dict(failed),
                successfulDbWithout202=sum(row['idempotency_key'] not in accepted_keys for row in success.values()),
                completeAccepted=not incomplete and not failed and len(delays) == len(accepted),
                clientEndToEndP99='未采集，DB时间不能替代客户端首次观察时间')


class SafetyGate:
    """基于实际采样时间累计持续阈值，熔断后不可复位。"""
    def __init__(self):
        self.high85 = self.high95 = None
        self.stop_reason = None
        self.no_escalation = False
        self.last = None

    def update(self, sample):
        now = sample['monotonic']
        if self.last is not None and now - self.last > 5:
            self.stop_reason = 'monitor_gap_over_5s'
        self.last = now
        cpu, free, used = sample['cpu'], sample['freeGiB'], sample['ramUsedPercent']
        self.high85 = (self.high85 if self.high85 is not None else now) if cpu > 85 else None
        self.high95 = (self.high95 if self.high95 is not None else now) if cpu > 95 else None
        if self.high85 is not None and now - self.high85 >= 15:
            self.no_escalation = True
        if self.high95 is not None and now - self.high95 >= 5:
            self.stop_reason = 'host_cpu_over_95_5s'
        if free < 4 or used > 85:
            self.no_escalation = True
            self.stop_reason = 'host_memory_guard'  # 比要求更保守：立即停新POST。
        if sample.get('unsafe'):
            self.stop_reason = sample['unsafe']
        return self.stop_reason
