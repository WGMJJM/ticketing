"""逐档运行相同场景并保留每次原始统计，不同时运行其他构建或压测。"""
import argparse
import perf

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--label', required=True)
parser.add_argument('--levels', default='10,25,50,100')
parser.add_argument('--scenarios', default='query,hot-ample,hot-scarce,mixed,waitlist,checkin')
parser.add_argument('--warmup', type=int, default=10)
parser.add_argument('--duration', type=int, default=20)
parser.add_argument('--k6')
args = parser.parse_args()
for scenario in args.scenarios.split(','):
    if scenario not in ['query','hot-ample','hot-scarce','mixed','waitlist','checkin']:
        raise RuntimeError('未知场景')
    for vus in map(int, args.levels.split(',')):
        print(f'开始 {args.label} {scenario} 并发 {vus}', flush=True)
        perf.prepare(scenario, vus)
        perf.run(argparse.Namespace(scenario=scenario, vus=vus, warmup=args.warmup,
                                    duration=args.duration, label=args.label, k6=args.k6))
