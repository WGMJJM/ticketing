# 活动详情关闭缓存短测

日期：2026-10-26。本次仅做一轮简短测试，观察公开活动详情每次回源 MySQL 的表现，不寻找最大稳定吞吐量。复用既有 JMeter JMX、断言和完成时间统计口径。

环境与上一份热点报告相同：本机 i5-13400F、16 逻辑处理器、约 32 GiB 内存，Java 21.0.10、JMeter 5.6.3；应用和 JMeter 同机，HTTP 访问 `127.0.0.1:18080`。依赖服务使用独立 `ticketing-jmeter` Compose 和 `ticketingjmeter` 数据库，V13，只有一个已发布活动。业务库未参与。

启动隔离应用前设置 `CATALOG_CACHE_ENABLED=false`，同时绕过活动目录 Caffeine 和 Redis 缓存。测前连续 3 次详情 GET，使 MySQL 对应活动主键查询计数增加 3，确认走数据库。MySQL 自身缓冲池保持正常，属于热数据主键读取，不是冷磁盘读取测试。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/jmeter/run-activity-rps.ps1 `
  -Threads 25 -RampUpSeconds 5 -WarmupSeconds 10 -MeasureSeconds 30 -DisableCache
```

| 并发线程 | 测量时长 | 全部/成功样本 | 成功 RPS | 总 RPS | 成功 P99 | 全样本 P99 | 失败 |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 25 | 30 秒 | 43,901 / 43,901 | 1,463.367 | 1,463.367 | 48 ms | 48 ms | 0 |

正式窗口 UTC 为 `2026-10-26T11:34:57.138Z` 至 `2026-10-26T11:35:27.138Z`。只统计活动详情 GET，HTTP 200、业务 `code=OK`、活动 ID 和发布状态匹配才算成功。P99 使用成功样本最近秩 `ceil(0.99 × N)`，爬坡、预热及窗口后样本均不混入。

此前缓存开启的 25 线程三轮中位数为 11,890.700 成功 RPS、13 ms P99，见 [热点读取报告](activity-detail-rps-p99-2026-10-26.md)。本次只跑一次，预热和测量较短，且隔离库重新创建；可观察缓存与回源路径的差别，不能作为严格的缓存收益 A/B 结论或线上容量。

原始压缩 JTL、日志、运行信息和摘要保留在本机忽略目录 `.local/jmeter/rps-p99/activity-rps-nocache-25-20261026-193421-1103/`。`database-read-evidence.json` 保存测前 3 请求/3 查询证据和数据库辅助计数。整轮数据库活动表读取约 7.2 万次，包含预检等额外读取；SQL digest 计数与 HTTP 数未完全一致，因此不拿数据库辅助计数逐请求对账或计算 RPS。核心指标全部来自正式窗口 JTL。

本次新增运行入口的 `-DisableCache` 选项并补充说明，未修改后端业务、数据库迁移或生产配置。结束后移除本次隔离应用、专用容器/网络/数据卷、临时凭据及 JMeter 安装，保留压缩结果以供复核。
