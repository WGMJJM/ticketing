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
    parser = argparse.ArgumentParser(description="只计算 SUCCEEDED 购票事务样本的端到端 P95")
    parser.add_argument("jtl", type=Path)
    parser.add_argument("--poll-interval-ms", type=int, default=250)
    args = parser.parse_args()
    if args.poll_interval_ms < 0:
        parser.error("poll 间隔不得为负数")
    if not args.jtl.is_file():
        parser.error(f"找不到 JTL 文件：{args.jtl}")

    with args.jtl.open("r", encoding="utf-8-sig", newline="") as stream:
        rows = list(csv.DictReader(stream))
    if not rows:
        print("JTL 无样本；成功订单 TPS 与端到端 P95 均为空。")
        return 0

    accepted = [row for row in rows if row.get("label") == SUBMIT and row.get("responseCode") == "202"]
    polls = [row for row in rows if row.get("label") == POLL]
    transactions = [row for row in rows if row.get("label") == TRANSACTION]
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
    batches = sorted({row.get("testBatch", "") for row in transactions if row.get("testBatch")})
    non_success_codes = Counter(row.get("responseCode", "") for row in rows if row.get("success", "").lower() == "false")

    print(f"JTL 样本总数：{len(rows)}")
    print(f"购票受理 HTTP 202：{len(accepted)}")
    print(f"状态轮询请求：{len(polls)}")
    print(f"购票端到端样本：{len(transactions)}")
    print(f"SUCCEEDED 且 orderId 非空：{len(successful)}")
    print(f"端到端 P95：{p95} ms（最近秩；仅成功样本，轮询间隔参数为 {args.poll_interval_ms} ms）" if p95 is not None else "端到端 P95：无有效成功样本")
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
