"""固定 [start,end) 窗口，区分POST受理、DB创建及窗口后排空；不推测客户端终态时刻。"""
from collections import Counter
from math import ceil


# 来自实际过滤器/Lua分支：这些返回发生在本次新预扣之前。其他503/网络错误均不作推断。
DEFINITE_REJECTIONS = frozenset(('RATE_LIMIT_UNAVAILABLE', 'TOO_MANY_REQUESTS', 'PURCHASE_QUEUE_FULL',
                                'TICKET_UNAVAILABLE', 'PURCHASE_LIMIT', 'WAITLIST_PRIORITY'))


def success_cohorts(posts, orders):
    by_key = {r['idempotency_key']: r for r in orders}
    main, recovered, outcomes = [], [], Counter()
    unresolved = []
    for post in posts:
        row = by_key.get(post['idempotencyKey'])
        matched = (row and row['status'] == 'SUCCEEDED' and row['order_id'] is not None
                   and str(row['id']) == post.get('requestId'))
        observed = matched and post.get('orderStatus') == 'SUCCEEDED' and post.get('orderCompletedMs')
        if post['responseCode'] == '202':
            if observed: main.append(post)
            continue
        outcome = 'SUCCEEDED' if matched else row['status'] if row else (
            'DEFINITE_NO_PREHOLD' if post.get('failureCode') in DEFINITE_REJECTIONS else 'UNKNOWN')
        outcomes[post.get('failureCode', '') + ':' + outcome] += 1
        if observed: recovered.append(post)
        if outcome in ('UNKNOWN', 'QUEUED'): unresolved.append(post['idempotencyKey'])
    def latency(rows):
        return dict(samples=len(rows), postToSettledMs=quantiles(
            int(p['orderCompletedMs'])-int(p['postStartMs']) for p in rows),
            postToClientObservedMs=quantiles(int(p['orderObservedMs'])-int(p['postStartMs']) for p in rows))
    return dict(success202=latency(main), recoveredNon202=latency(recovered),
                non202FinalOutcomes=dict(outcomes), unknownKeys=unresolved)


def quantiles(values):
    values = sorted(values)
    return {f'p{int(p * 100)}': values[ceil(len(values) * p) - 1] if values else None
            for p in (.5, .95, .99)}


def warmup_stability(completions, now_ms):
    """最近三个完整5秒段的成功建单完成率，极差不超过均值的20%。"""
    rates = [sum(left <= row['completedMs'] < left+5000 and row['status'] == 'SUCCEEDED'
                 for row in completions) / 5 for left in range(now_ms-15000, now_ms, 5000)]
    average = sum(rates)/len(rates)
    return dict(rates=rates, stable=average > 0 and max(rates)-min(rates) <= average*.20)


def has_persistent_pending_growth(samples):
    """实时预扣积压持续增长；Rabbit管理统计的刷新跳变不能替代实时证据。"""
    return (len(samples) >= 5 and samples[-1]['redisPending'] > samples[0]['redisPending']+30
            and all(b['redisPending'] >= a['redisPending'] for a,b in zip(samples,samples[1:])))


def capacity_pressure_reason(pending, queued, capacity, allow_capacity_fill=False):
    """显式满容量闭环允许等于上限；默认仍在75%处停发，超过权威容量始终停止。"""
    if allow_capacity_fill:
        if pending > capacity: return 'redis_pending_exceeds_capacity'
        if queued > capacity: return 'queue_exceeds_capacity'
    else:
        if pending >= capacity * .75: return 'redis_pending_reaches_75_percent_capacity'
        if queued >= capacity * .75: return 'queue_reaches_75_percent_capacity'
    return None


def summarize(posts, orders, start_ms, end_ms):
    if end_ms <= start_ms:
        raise ValueError('固定窗口结束须晚于开始')
    window = [p for p in posts if start_ms <= int(p['postStartMs']) < end_ms]
    keys = [p['idempotencyKey'] for p in posts]
    counts = Counter(p['responseCode'] for p in window)
    accepted = [p for p in window if p['responseCode'] == '202' and p.get('requestId', '').isdigit()]
    accepted_keys = {p['idempotencyKey'] for p in posts if p['responseCode'] == '202' and p.get('requestId', '').isdigit()}
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
