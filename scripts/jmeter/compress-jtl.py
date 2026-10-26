#!/usr/bin/env python3
"""无损压缩已分析的 CSV JTL，以便在本地保留原始样本证据。"""

from __future__ import annotations

import gzip
import shutil
import sys
from pathlib import Path


def main() -> int:
    if len(sys.argv) != 3:
        print("用法：compress-jtl.py <source.jtl> <target.jtl.gz>", file=sys.stderr)
        return 2
    source = Path(sys.argv[1]).resolve()
    target = Path(sys.argv[2]).resolve()
    if source.suffix != ".jtl" or target.suffixes[-2:] != [".jtl", ".gz"]:
        raise ValueError("仅接受 .jtl 到 .jtl.gz 的压缩")
    if source.parent != target.parent or not source.is_file() or target.exists():
        raise ValueError("源文件或目标目录不符合单批次压缩要求")
    with source.open("rb") as raw, gzip.open(target, "wb", compresslevel=6) as compressed:
        shutil.copyfileobj(raw, compressed)
    source.unlink()
    print(f"已保留无损压缩 JTL：{target}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"JTL 压缩失败：{error}", file=sys.stderr)
        raise SystemExit(1)
