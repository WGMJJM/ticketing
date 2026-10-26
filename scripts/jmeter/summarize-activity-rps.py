#!/usr/bin/env python3
"""按正式测量窗口汇总单活动详情 JMeter CSV JTL。"""

from __future__ import annotations

import argparse
import csv
import gzip
import json
import math
import statistics
import sys
from datetime import datetime, timezone
from pathlib import Path


LABEL = "活动详情热点查询"
MARKER = "RPS timing marker"


def percentile(values: list[int], percentile_value: float) -> int | None:
    if not values:
        return None
    ordered = sorted(values)
    rank = max(1, math.ceil(percentile_value * len(ordered)))
    return ordered[rank - 1]


def utc_from_epoch_ms(epoch_ms: int) -> str:
    return datetime.fromtimestamp(epoch_ms / 1000, timezone.utc).isoformat(timespec="milliseconds")


def main() -> int:
    parser = argparse.ArgumentParser(description="仅汇总活动详情 JMeter JTL 的正式窗口样本")
    parser.add_argument("--jtl", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--batch", required=True)
    parser.add_argument("--activity-id", required=True)
    parser.add_argument("--threads", required=True, type=int)
    parser.add_argument("--ramp-up-seconds", required=True, type=int)
    parser.add_argument("--warmup-seconds", required=True, type=int)
    parser.add_argument("--measure-seconds", required=True, type=int)
    args = parser.parse_args()
    if min(args.threads, args.measure_seconds) < 1 or min(args.ramp_up_seconds, args.warmup_seconds) < 0:
        parser.error("线程数和测量秒数必须为正，爬坡和预热时长不能为负")
    if not args.jtl.is_file():
        parser.error(f"找不到 JTL：{args.jtl}")

    opener = gzip.open if args.jtl.suffix == ".gz" else open
    with opener(args.jtl, "rt", encoding="utf-8-sig", newline="") as stream:
        rows = list(csv.DictReader(stream))
    markers = [row for row in rows if row.get("label") == MARKER and row.get("timeStamp", "").isdigit()]
    if len(markers) != 1:
        raise ValueError(f"应有且仅有一个时间标记样本，实际为 {len(markers)}")
    run_start_ms = int(markers[0]["timeStamp"])
    measure_start_ms = run_start_ms + (args.ramp_up_seconds + args.warmup_seconds) * 1000
    measure_end_ms = measure_start_ms + args.measure_seconds * 1000

    target_rows: list[tuple[int, int, bool, str]] = []
    phase_marker_mismatches = 0
    outside = {"RAMP": 0, "WARMUP": 0, "POST": 0}
    for row in rows:
        if row.get("label") != LABEL:
            continue
        try:
            start_ms = int(row["timeStamp"])
            elapsed_ms = int(row["elapsed"])
        except (KeyError, ValueError):
            continue
        end_ms = start_ms + elapsed_ms
        if end_ms < measure_start_ms:
            phase = "RAMP" if end_ms < run_start_ms + args.ramp_up_seconds * 1000 else "WARMUP"
            outside[phase] += 1
            continue
        if end_ms >= measure_end_ms:
            outside["POST"] += 1
            continue
        if row.get("samplePhase") != "MEASURE":
            phase_marker_mismatches += 1
        success = row.get("success", "").lower() == "true" and row.get("responseCode") == "200" and row.get("businessOk", "").lower() == "true"
        failure_code = row.get("failureCode", "") or ("HTTP_" + row.get("responseCode", "UNKNOWN") if row.get("responseCode") != "200" else "ASSERTION_FAILED")
        target_rows.append((end_ms, elapsed_ms, success, failure_code))

    total_count = len(target_rows)
    successes = [sample for sample in target_rows if sample[2]]
    failures = [sample for sample in target_rows if not sample[2]]
    durations_all = [sample[1] for sample in target_rows]
    durations_success = [sample[1] for sample in successes]
    seconds = float(args.measure_seconds)
    bucket_width_ms = 10_000
    bucket_count = math.ceil(args.measure_seconds * 1000 / bucket_width_ms)
    buckets = []
    for index in range(bucket_count):
        left = measure_start_ms + index * bucket_width_ms
        right = min(measure_end_ms, left + bucket_width_ms)
        samples = [sample for sample in target_rows if left <= sample[0] < right]
        successful_bucket = [sample for sample in samples if sample[2]]
        span_seconds = (right - left) / 1000
        buckets.append({
            "startUtc": utc_from_epoch_ms(left),
            "endUtc": utc_from_epoch_ms(right),
            "samples": len(samples),
            "successfulSamples": len(successful_bucket),
            "totalRps": round(len(samples) / span_seconds, 3),
            "successRps": round(len(successful_bucket) / span_seconds, 3),
            "successP99Ms": percentile([sample[1] for sample in successful_bucket], 0.99),
        })

    error_rate = len(failures) / total_count if total_count else None
    non_empty_buckets = [bucket for bucket in buckets if bucket["samples"]]
    first_p99 = [bucket["successP99Ms"] for bucket in non_empty_buckets[:3] if bucket["successP99Ms"] is not None]
    last_p99 = [bucket["successP99Ms"] for bucket in non_empty_buckets[-3:] if bucket["successP99Ms"] is not None]
    p99_degraded = bool(first_p99 and last_p99 and statistics.median(last_p99) > statistics.median(first_p99) * 2)
    rate_reference = statistics.median([bucket["successRps"] for bucket in non_empty_buckets]) if non_empty_buckets else 0
    collapsed_twice = any(
        buckets[index]["successRps"] < rate_reference * 0.5
        and buckets[index + 1]["successRps"] < rate_reference * 0.5
        for index in range(len(buckets) - 1)
    )
    validity_reasons = []
    if total_count < 1000:
        validity_reasons.append("正式窗口样本少于 1000")
    if error_rate is None or error_rate > 0.01:
        validity_reasons.append("错误率超过 1% 或没有正式窗口样本")
    if collapsed_twice:
        validity_reasons.append("存在连续两个 10 秒桶成功吞吐低于非空桶中位数的 50%")
    if p99_degraded:
        validity_reasons.append("末尾三个 10 秒桶 P99 中位数超过开头三个桶的两倍")
    if phase_marker_mismatches:
        validity_reasons.append("JMeter 样本阶段标记与固定窗口时间边界不一致")

    failure_counts: dict[str, int] = {}
    for _, _, _, code in failures:
        failure_counts[code] = failure_counts.get(code, 0) + 1
    result = {
        "batch": args.batch,
        "activityId": args.activity_id,
        "threads": args.threads,
        "rampUpSeconds": args.ramp_up_seconds,
        "warmupSeconds": args.warmup_seconds,
        "measureSeconds": args.measure_seconds,
        "measurementStartUtc": utc_from_epoch_ms(measure_start_ms),
        "measurementEndUtc": utc_from_epoch_ms(measure_end_ms),
        "measurementWindowSource": "JMeter timing marker + configured phase seconds; completion time = timeStamp + elapsed",
        "totalSamples": total_count,
        "successfulSamples": len(successes),
        "failedSamples": len(failures),
        "successRps": round(len(successes) / seconds, 3),
        "totalRps": round(total_count / seconds, 3),
        "errorRate": round(error_rate, 6) if error_rate is not None else None,
        "successP99Ms": percentile(durations_success, 0.99),
        "allSamplesP99Ms": percentile(durations_all, 0.99),
        "p99Method": "nearest-rank ceil(0.99 * N)",
        "failureCodes": dict(sorted(failure_counts.items())),
        "outsideWindowSamples": outside,
        "phaseMarkerMismatches": phase_marker_mismatches,
        "tenSecondBuckets": buckets,
        "stabilityChecks": {
            "minimumSamples1000": total_count >= 1000,
            "errorRateAtMost1Percent": error_rate is not None and error_rate <= 0.01,
            "noTwoBucketThroughputCollapse": not collapsed_twice,
            "noEndP99MedianAbove2xStartP99Median": not p99_degraded,
            "phaseMarkersMatchWindow": phase_marker_mismatches == 0,
            "validUnderDeclaredRule": not validity_reasons,
            "invalidReasons": validity_reasons,
            "rule": "至少 1000 个正式样本、错误率不高于 1%、无连续两个 10 秒桶低于非空桶成功 RPS 中位数 50%、末尾三个桶 P99 中位数不超过开头三个桶的两倍；此规则不是业务 SLA。",
        },
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: result[key] for key in (
        "batch", "threads", "measurementStartUtc", "measurementEndUtc", "totalSamples",
        "successfulSamples", "failedSamples", "successRps", "totalRps", "errorRate",
        "successP99Ms", "allSamplesP99Ms", "failureCodes", "stabilityChecks",
    )}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"活动详情 JTL 汇总失败：{error}", file=sys.stderr)
        raise SystemExit(1)
