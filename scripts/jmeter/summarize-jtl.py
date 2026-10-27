#!/usr/bin/env python3
"""从 JMeter CSV JTL 中提取购票链路成功样本与失败分类。"""

from __future__ import annotations

import argparse
import csv
import math
import statistics
import sys
from collections import Counter
from pathlib import Path


TRANSACTION = "订单创建端到端"
SUBMIT = "购票申请受理"
POLL = "查询购票申请状态"


def main() -> int:
    parser = argparse.ArgumentParser(description="只计算 SUCCEEDED 购票事务样本的端到端 P99")
    parser.add_argument("jtl", type=Path)
    parser.add_argument("--poll-interval-ms", type=int, default=250)
    parser.add_argument("--window-start-ms", type=int, help="事务开始时间下界，Unix 毫秒（含）")
    parser.add_argument("--window-end-ms", type=int, help="事务开始时间上界，Unix 毫秒（不含）")
    args = parser.parse_args()
    if args.poll_interval_ms < 0:
        parser.error("poll 间隔不得为负数")
    if (args.window_start_ms is None) != (args.window_end_ms is None):
        parser.error("窗口起止毫秒必须同时提供")
    if args.window_start_ms is not None and args.window_start_ms >= args.window_end_ms:
        parser.error("窗口结束必须晚于开始")
    if not args.jtl.is_file():
        parser.error(f"找不到 JTL 文件：{args.jtl}")

    with args.jtl.open("r", encoding="utf-8-sig", newline="") as stream:
        rows = list(csv.DictReader(stream))
    if not rows:
        print("JTL 无样本；成功订单 TPS 与端到端 P99 均为空。")
        return 0

    def in_window(row: dict[str, str]) -> bool:
        return args.window_start_ms is None or args.window_start_ms <= int(row["timeStamp"]) < args.window_end_ms

    submissions = [row for row in rows if row.get("label") == SUBMIT and in_window(row)]
    accepted = [row for row in submissions if row.get("responseCode") == "202"]
    polls = [row for row in rows if row.get("label") == POLL and in_window(row)]
    transactions = [row for row in rows if row.get("label") == TRANSACTION]
    transactions = [row for row in transactions if in_window(row)]
    successful: list[dict[str, str]] = []
    failures: Counter[str] = Counter()
    for row in transactions:
        if (
            row.get("purchaseOutcome") == "SUCCEEDED"
            and row.get("orderId", "").strip()
            and row.get("success", "").lower() == "true"
        ):
            successful.append(row)
        else:
            failures[row.get("purchaseOutcome", "UNKNOWN") or "UNKNOWN"] += 1
    durations = sorted(int(row["elapsed"]) for row in successful if row.get("elapsed", "").isdigit())
    p95 = durations[max(0, math.ceil(len(durations) * 0.95) - 1)] if durations else None
    p99 = durations[max(0, math.ceil(len(durations) * 0.99) - 1)] if durations else None
    batches = sorted({row.get("testBatch", "") for row in transactions if row.get("testBatch")})
    non_success_codes = Counter(row.get("responseCode", "") for row in rows if in_window(row) and row.get("success", "").lower() == "false")

    print(f"JTL 样本总数：{len(rows)}")
    print(f"购票受理 HTTP 202：{len(accepted)}")
    print(f"状态轮询请求：{len(polls)}")
    print(f"购票端到端样本：{len(transactions)}")
    print(f"SUCCEEDED 且 orderId 非空：{len(successful)}")
    print(f"端到端 P95：{p95} ms（最近秩；仅成功样本，轮询间隔参数为 {args.poll_interval_ms} ms）" if p95 is not None else "端到端 P95：无有效成功样本")
    print(f"端到端 P99：{p99} ms（最近秩；{len(durations)} 个有效成功样本，轮询间隔 {args.poll_interval_ms} ms）" if p99 is not None else "端到端 P99：无有效成功样本")
    if submissions and all(row.get("idempotencyKey") for row in submissions):
        completed_keys = {row.get("idempotencyKey") for row in transactions}
        missing = sum(row["idempotencyKey"] not in completed_keys for row in submissions)
        print(f"已提交但缺少完整事务样本：{missing}")
        if missing:
            print("警告：事务样本存在截尾，成功样本 P99 不能代表完整窗口的端到端 P99。")
    else:
        print("未能按幂等键核查事务样本完整性；请确保 JTL 包含 idempotencyKey。")
    print(f"失败分类：{dict(sorted(failures.items()))}")
    print(f"失败 HTTP 状态：{dict(sorted(non_success_codes.items()))}")
    print(f"批次：{batches}")
    print("成功订单 TPS 请用 verify-orders.sql 按 MySQL UTC 创建时间及正式窗口计算。")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"JTL 摘要失败：{error}", file=sys.stderr)
        raise SystemExit(1)
