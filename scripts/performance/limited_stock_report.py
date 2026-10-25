"""Render aggregate evidence from completed limited-stock runs; never connects to services."""
import argparse
import json
from pathlib import Path
import re

import perf


def metric_values(samples, name, statistic):
    return [measurement['value'] for sample in samples
            for measurement in sample.get('jvm', {}).get(name, []) or []
            if measurement['statistic'] == statistic]


def summarize(report, samples):
    result = {key: report[key] for key in ('label', 'scenario', 'passed', 'attempted', 'accepted202',
        'categories', 'terminalStates', 'terminalFailureCodes', 'httpMs', 'acceptedMs', 'capacityRejectedMs',
        'successfulTerminalObservedMs', 'allTerminalObservedMs', 'submissionSeconds', 'batchHttpRps',
        'batchAcceptedRps', 'wholeBatchAndDrainSeconds', 'batchSuccessfulOrderRpsIncludingDrain',
        'restartAndDrainSeconds', 'sampledPeakPending', 'sampledPeakOutboxPending', 'mysqlDelta')}
    result['sampledJvmPeaks'] = {}
    for name, statistic in (('hikaricp.connections.active', 'VALUE'), ('hikaricp.connections.pending', 'VALUE'),
                            ('hikaricp.connections.max', 'VALUE'), ('hikaricp.connections.timeout', 'COUNT'),
                            ('hikaricp.connections.acquire', 'MAX'), ('process.cpu.usage', 'VALUE'),
                            ('jvm.memory.used', 'VALUE'), ('jvm.gc.pause', 'MAX'), ('jvm.threads.live', 'VALUE')):
        values = metric_values(samples, name, statistic)
        result['sampledJvmPeaks'][name] = max(values) if values else None
    result['sampledPeakOutboxOldestMs'] = max([float(channel['oldest_ms'] or 0)
        for sample in samples for channel in sample.get('outboxBacklogAge', [])] + [0])
    result['sampledRabbitPeaks'] = {key: max([sum(queue.get(key) or 0 for queue in sample.get('rabbitmq', []))
        for sample in samples] + [0]) for key in ('messages_ready', 'messages_unacknowledged')}
    result['scope'] = 'Peaks are sampled every >=2s; restart gaps have no JVM measurements. Counter maxima are not sums across process restarts.'
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--labels', nargs=3, required=True, help='A, B, C public result directory labels')
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    for label in args.labels:
        if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,59}', label):
            raise RuntimeError('Invalid input label')
    if args.output.exists():
        raise RuntimeError('Summary output directory already exists')
    evidence = []
    for label in args.labels:
        directory = perf.RESULTS / label
        report = json.loads((directory / 'report.json').read_text(encoding='utf-8'))
        samples = json.loads((directory / 'monitoring.json').read_text(encoding='utf-8'))
        evidence.append((report, samples))
    if [report['scenario'] for report, _ in evidence] != ['A', 'B', 'C']:
        raise RuntimeError('Expected exactly A, B, C in that order')
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    figure, axes = plt.subplots(3, 1, figsize=(11, 9), layout='constrained')
    for axis, (report, samples) in zip(axes, evidence):
        x, budget, outbox, ready, unacked = [], [], [], [], []
        previous_phase = None
        for sample in samples:
            if previous_phase is not None and sample['phase'] != previous_phase:
                for series in (x, budget, outbox, ready, unacked):
                    series.append(float('nan'))
            x.append(sample['batchSeconds'])
            budget.append(sample['purchaseCapacity'][0]['pending_count'])
            outbox.append(sum(row['n'] for row in sample['outbox'] if row['status'] in ('PENDING', 'PROCESSING')))
            ready.append(sum(row.get('messages_ready') or 0 for row in sample.get('rabbitmq', [])))
            unacked.append(sum(row.get('messages_unacknowledged') or 0 for row in sample.get('rabbitmq', [])))
            previous_phase = sample['phase']
        for values, label, color in ((budget, 'Nonterminal budget', '#2166ac'),
                                     (outbox, 'Outbox pending/processing', '#d6604d'),
                                     (ready, 'Rabbit ready', '#238b45'),
                                     (unacked, 'Rabbit unacked', '#6a51a3')):
            axis.plot(x, values, marker='.', markersize=4, label=label, color=color)
        if report['passed']:
            axis.plot(report['wholeBatchAndDrainSeconds'], 0, marker='D', markerfacecolor='none',
                      color='#222222', linestyle='none', label='Drain guard (time upper bound)')
        axis.set(title=f"{report['scenario']}: {report['attempted']} buyers, 100 tickets, {report['accepted202']} accepted",
                 xlabel='Seconds since batch preparation finished', ylabel='Sampled records / messages')
        axis.grid(alpha=.2)
        axis.legend(loc='upper right', fontsize=8)
    figure.suptitle('Lines: periodic samples; gaps: application restart; hollow diamonds: verified drain, time upper bound', fontsize=10)
    args.output.mkdir(parents=True)
    figure.savefig(args.output / 'queue-depth.png', dpi=160)
    plt.close(figure)
    perf.save(args.output / 'summary.json', {'runs': [summarize(report, samples) for report, samples in evidence]})
    print('Wrote aggregate summary and sampled queue-depth chart')


if __name__ == '__main__':
    main()
