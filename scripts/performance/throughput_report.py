"""Offline repeat medians and before/after comparisons; never pool per-run percentiles."""
import argparse
import hashlib
import json
import re
from statistics import median
import xml.etree.ElementTree as ET

import perf


def record_validation(label, verification_label):
    if not all(re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,49}', value)
               for value in (label, verification_label)):
        raise RuntimeError('Invalid validation label')
    output = perf.RESULTS / label / 'validation.json'
    if not output.parent.is_dir() or output.exists():
        raise RuntimeError('Use an existing summary directory and a new validation file')
    groups = {}
    for name, directory in [('ordinary', 'surefire-reports'), ('integration', 'failsafe-reports')]:
        suites = []
        for path in sorted((perf.ROOT / 'backend' / 'target' / directory).glob('TEST-*.xml')):
            root = ET.parse(path).getroot()
            suites.append({'name': root.attrib['name'],
                **{key: int(root.attrib.get(key, '0')) for key in ('tests', 'failures', 'errors', 'skipped')},
                'xmlSha256': hashlib.sha256(path.read_bytes()).hexdigest()})
        if not suites or any(s[key] for s in suites for key in ('failures', 'errors', 'skipped')):
            raise RuntimeError('Missing or unsuccessful real JUnit evidence')
        groups[name] = {'totals': {key: sum(s[key] for s in suites)
                                 for key in ('tests', 'failures', 'errors', 'skipped')}, 'suites': suites}
    sources = ['backend/src/main/java/com/ticketing/admission/PurchaseAdmission.java',
        'backend/src/main/java/com/ticketing/admission/PurchaseRequestMapper.java',
        'backend/src/main/resources/application.yml',
        'backend/src/test/java/com/ticketing/JdbcSessionStateIT.java',
        'backend/src/test/java/com/ticketing/messaging/AsyncPurchaseIT.java',
        'scripts/performance/perf.py', 'scripts/performance/application-performance.yml',
        'scripts/performance/throughput_tuning.py', 'scripts/performance/throughput_report.py',
        'scripts/performance/test_throughput_tuning.py']
    if (perf.PRIVATE / 'app.pid').exists():
        raise RuntimeError('Stop the performance application before recording final validation')
    perf.save(output, {'command': 'python -u -B scripts/performance/verify_backend.py --label ' + verification_label,
        'groups': groups, 'uniqueTests': sum(g['totals']['tests'] for g in groups.values()),
        'sourceHashes': {p: hashlib.sha256((perf.ROOT / p).read_bytes()).hexdigest() for p in sources},
        'performanceApplicationStopped': True})


def read(labels):
    reports = []
    for label in labels:
        if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,49}', label):
            raise RuntimeError('Invalid source label')
        report = json.loads((perf.RESULTS / label / 'comparison.json').read_text(encoding='utf-8'))
        if not report['passed']:
            raise RuntimeError('Cannot use an incomplete or failed performance run')
        reports.append({'label': label, **report})
    return reports


def aggregate(reports):
    result = {}
    for vus in sorted({row['vus'] for report in reports for row in report['rounds']}):
        rows = [row for report in reports for row in report['rounds'] if row['vus'] == vus]
        result[vus] = {'runs': len(rows), 'tpsMedian': median(r['successfulOrderTps'] for r in rows),
            'tpsRange': [min(r['successfulOrderTps'] for r in rows), max(r['successfulOrderTps'] for r in rows)],
            'successP95MedianMs': median(r['successCompletionMs']['p(95)'] for r in rows),
            'acceptanceP95MedianMs': median(r['acceptanceMs']['p(95)'] for r in rows)}
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline', nargs='+', required=True)
    parser.add_argument('--candidate', nargs='+', required=True)
    parser.add_argument('--diagnostic', nargs='*', default=[])
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,49}', args.output):
        raise RuntimeError('Invalid new output label')
    output = perf.RESULTS / args.output
    if output.exists():
        raise RuntimeError('Never overwrite existing evidence')
    before, after, diagnostics = read(args.baseline), read(args.candidate), read(args.diagnostic)
    if len({(r['warmupSeconds'], r['steadySeconds']) for r in before + after}) != 1:
        raise RuntimeError('Comparison windows must match')
    for group in (before, after):
        normalized = [{**{'WAITLIST_OUTBOX_POLL_MS': '1000', 'PERF_JDBC_LOCAL_SESSION_STATE': 'false'},
                       **r['config']} for r in group]
        if len({json.dumps(c, sort_keys=True) for c in normalized}) != 1:
            raise RuntimeError('Each comparison group must use identical settings')
    comparisons = []
    baseline, candidate = aggregate(before), aggregate(after)
    if set(baseline) != set(candidate):
        raise RuntimeError('Both sides must cover the same concurrency steps')
    for vus in baseline:
        b, a = baseline[vus], candidate[vus]
        comparisons.append({'vus': vus, 'before': b, 'after': a,
            'tpsIncreasePercent': (a['tpsMedian'] / b['tpsMedian'] - 1) * 100,
            'successP95ReductionPercent': (1 - a['successP95MedianMs'] / b['successP95MedianMs']) * 100,
            'acceptanceP95ReductionPercent': (1 - a['acceptanceP95MedianMs'] / b['acceptanceP95MedianMs']) * 100})
    perf.save(output / 'summary.json', {'comparisons': comparisons, 'baselineReports': before,
        'candidateReports': after, 'diagnosticReports': diagnostics,
        'scope': 'Medians of independent finite steady windows; P95 medians are not pooled request P95 or a confidence interval.'})
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    figure, axes = plt.subplots(1, 2, figsize=(10, 4), layout='constrained')
    for name, group, color in [('Before', baseline, '#247b91'), ('After', candidate, '#b14a40')]:
        axes[0].plot(list(group), [r['tpsMedian'] for r in group.values()], 'o-', label=name, color=color)
        axes[1].plot(list(group), [r['successP95MedianMs'] / 1000 for r in group.values()], 'o-', label=name, color=color)
    axes[0].set_ylabel('Successful orders / second')
    axes[1].set_ylabel('Observed success P95 (seconds)')
    for axis in axes:
        axis.set_xlabel('Concurrent independent buyers')
        axis.set_xticks(list(baseline))
        axis.grid(alpha=.2)
        axis.legend()
    figure.savefig(output / 'comparison.png', dpi=160)
    plt.close(figure)
    print(json.dumps(comparisons, indent=2))


if __name__ == '__main__':
    main()
