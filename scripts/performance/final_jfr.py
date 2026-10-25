"""仅固定专用JVM录制；共享结果只有分布和代码栈，原始逐消息技术关联留在.local。"""
from collections import Counter, defaultdict
import hashlib
import html
import json
import re
import subprocess
from pathlib import Path
import perf
from chain_metrics import distribution


def begin(label):
    if not re.fullmatch(r'[A-Za-z0-9_-]{1,80}', label):
        raise RuntimeError('JFR标签无效')
    perf.connect().close()
    pid = int((perf.PRIVATE / 'app.pid').read_text())
    command = subprocess.check_output(['jcmd', str(pid), 'VM.command_line'], text=True)
    if 'scripts/performance/application-performance.yml' not in command:
        raise RuntimeError('JFR目标不是固定专用JVM')
    directory = perf.ROOT / '.local/stage19/recordings'
    directory.mkdir(parents=True, exist_ok=True)
    raw = directory / (label + '.jfr')
    if raw.exists():
        raise RuntimeError('JFR证据已存在')
    result = subprocess.run(['jcmd', str(pid), 'JFR.start', 'name=ticketing_stage19',
        'settings=profile', 'maxsize=128m'], check=True, capture_output=True, text=True)
    (directory / (label + '-start.log')).write_text(result.stdout + result.stderr, encoding='utf-8')
    return pid, raw


def stage_report(events):
    groups = defaultdict(dict)
    accepted = {}
    for event in events:
        value = event['values']
        key = value.get('deliveryKey', '')
        if not re.fullmatch(r'purchase-[1-9][0-9]*:[1-9][0-9]*', key):
            continue
        stage, at = value['stage'], int(value['monotonicNanos'])
        groups[key].setdefault(stage, at)
        if stage == 'accepted-commit':
            accepted[key.split(':')[0]] = at
    pairs = {
        'acceptedCommitToOutboxClaimMs': ('accepted-commit', 'outbox-claim-commit'),
        'outboxClaimToBrokerConfirmMs': ('outbox-claim-commit', 'broker-confirm'),
        'brokerConfirmToConsumerClaimSignedMs': ('broker-confirm', 'consumer-claim-commit'),
        'consumerClaimToOrderCommitMs': ('consumer-claim-commit', 'order-transaction-commit'),
        'acceptedCommitToOrderCommitMs': ('accepted-commit', 'order-transaction-commit'),
        'acceptedCommitToBusinessFailureMs': ('accepted-commit', 'business-failure-commit'),
    }
    result = {}
    for name, (first, second) in pairs.items():
        values = []
        for key, group in groups.items():
            a = accepted.get(key.split(':')[0]) if first == 'accepted-commit' else group.get(first)
            b = group.get(second)
            if a is not None and b is not None:
                values.append((b - a) / 1000000)
        result[name] = {**distribution(values), 'negativeSamples': sum(v < 0 for v in values)}
    result.update(observedDeliveryAttempts=len(groups), acceptedObserved=len(accepted),
        scope='同一JVM单调时钟关联实际提交/Confirm回调时刻；全轮样本，非k6稳定窗口。',
        overlap='Confirm→消费者认领允许负数，表示消费先于发布确认完成；保留符号，分位数不能直接相加。',
        incompleteGroups=sum('broker-confirm' not in g or 'consumer-claim-commit' not in g for g in groups.values()))
    return result


def finish(recording, output):
    pid, raw = recording
    result = subprocess.run(['jcmd', str(pid), 'JFR.stop', 'name=ticketing_stage19', 'filename=' + str(raw)],
        capture_output=True, text=True, check=True)
    raw.with_suffix('.stop.log').write_text(result.stdout + result.stderr, encoding='utf-8')
    observations = json.loads(subprocess.check_output(['jfr', 'print', '--json', '--events',
        'com.ticketing.DeliveryObservation', str(raw)]))['recording']['events']
    report = stage_report(observations)
    report.update(rawRecording=str(raw.relative_to(perf.ROOT)), rawSha256=hashlib.sha256(raw.read_bytes()).hexdigest())
    if not report['observedDeliveryAttempts']:
        raise RuntimeError('本轮没有JFR业务阶段观察，不能生成分段通过结论')
    perf.save(output / 'delivery-stages.json', report)
    cpu_events = json.loads(subprocess.check_output(['jfr', 'print', '--json', '--events', 'jdk.ExecutionSample', str(raw)]))['recording']['events']
    stacks = Counter()
    for event in cpu_events:
        frames = (event['values'].get('stackTrace') or {}).get('frames') or []
        names = [f['method']['type']['name'].replace('/', '.') + '.' + f['method']['name'] for f in reversed(frames)]
        if names:
            stacks[tuple(names[-24:])] += 1
    perf.save(output / 'cpu-stacks.json', {'sampleCount': sum(stacks.values()),
        'stacks': [{'frames': list(stack), 'samples': count} for stack, count in stacks.most_common(200)],
        'scope': '全轮JFR统计抽样栈，不能据此推断某个SQL的锁持有时间。'})
    flamegraph(stacks, output / 'cpu-flamegraph.svg')
    return report


def flamegraph(stacks, path):
    root = {'count': 0, 'children': {}}
    for stack, count in stacks.items():
        current = root
        current['count'] += count
        for name in stack:
            current = current['children'].setdefault(name, {'count': 0, 'children': {}})
            current['count'] += count
    svg = ['<svg xmlns="http://www.w3.org/2000/svg" width="1200" height="470" viewBox="0 0 1200 470">',
        '<rect width="1200" height="470" fill="#fff"/><text x="10" y="22" font-size="16">Stage19 JFR CPU samples (whole run)</text>']
    def draw(node, x, width, depth):
        offset = x
        for name, child in sorted(node['children'].items()):
            w = width * child['count'] / max(1, node['count'])
            if w >= 1:
                y = 435 - depth * 16
                svg.append(f'<g><title>{html.escape(name)}: {child["count"]} samples</title><rect x="{offset:.2f}" y="{y}" width="{w:.2f}" height="15" fill="hsl({30 + depth * 4},85%,70%)" stroke="white" stroke-width="0.4"/>')
                if w > 50:
                    label = name[-max(1, int(w / 6)):]
                    svg.append(f'<text x="{offset + 3:.2f}" y="{y + 11}" font-size="10">{html.escape(label)}</text>')
                svg.append('</g>')
                if depth < 23:
                    draw(child, offset, w, depth + 1)
            offset += w
    draw(root, 5, 1190, 0)
    svg.append('</svg>')
    path.write_text('\n'.join(svg), encoding='utf-8')


if __name__ == '__main__':
    # 允许消费先于Confirm；缺失阶段不能伪造为0ms。原始技术键不会进入报告。
    events = [{'values': {'deliveryKey': 'purchase-1:1', 'stage': stage, 'monotonicNanos': at}}
              for stage, at in [('accepted-commit', 1000000), ('outbox-claim-commit', 2000000),
                                ('consumer-claim-commit', 3000000), ('broker-confirm', 4000000),
                                ('order-transaction-commit', 5000000)]]
    result = stage_report(events)
    assert result['brokerConfirmToConsumerClaimSignedMs']['p50'] == -1
    assert result['acceptedCommitToOrderCommitMs']['p50'] == 4
    assert stage_report([])['consumerClaimToOrderCommitMs']['p50'] is None
    print('3项阶段关联与负时差/缺样本检查通过')
