# 活动详情 RPS 与 P99 实测报告

测试日期：2026-10-26。主场景仅请求公开接口 `GET /api/v1/activities/1`，活动为 `ticketing-jmeter` 隔离库中的已发布测试夹具。响应校验要求 HTTP 200、`code=OK`、`data.id=1`、`data.status=PUBLISHED`。每个测量窗口都只统计这一种 HTTP 请求。

## 测试环境与方法

- Windows 11 专业版；Intel Core i5-13400F，16 个逻辑处理器，约 32 GiB 内存。
- Docker Engine 29.5.2，隔离容器：MySQL 8.4、Redis 7、RabbitMQ 4.1；隔离 schema `ticketingjmeter`，Flyway V13。
- 后端 Java 21.0.10，运行在同一台主机的 `127.0.0.1:18080`；JMeter 5.6.3 CLI 也在本机运行。JMeter 从 [Apache 官方下载目录](https://jmeter.apache.org/download_jmeter.cgi) 获取并通过官方 SHA-512 校验。
- 业务缓存显式开启：Caffeine L1 15 秒、Redis L2 120 秒。单轮预检后，固定同一活动 ID，并使用 60 秒预热后进入 120 秒测量窗口。压测期间无写请求。
- JMeter 闭环并发线程，无吞吐量定时器。成功 RPS 是实际完成的有效业务 GET 数除以 120 秒，不是设定到达率，也不是购票 TPS。
- 测量窗口根据唯一 JMeter 起点标记、配置的爬坡/预热时长确定；用样本完成时刻 `timeStamp + elapsed` 归窗。P99 仅统计成功业务响应，采用最近秩 `ceil(0.99 × N)`；另算全部样本 P99，本表数值相同。错误率计入 HTTP 或业务校验失败、超时和非 HTTP 客户端异常。

| 并发线程 | 批次 | 测量窗口 UTC | 全部/成功样本 | 成功 RPS | 总 RPS | 成功 P99 (ms) | 错误率与结果 |
|---:|---|---|---:|---:|---:|---:|---|
| 10 | `activity-rps-10-20261026-180341-9645` | 10:05:25.681–10:07:25.681 | 1,109,751 / 1,109,749 | 9,247.908 | 9,247.925 | 2 | 2 个客户端 `BindException`，0.0002%；筛查通过 |
| 25 | `activity-rps-25-20261026-180802-4498` | 10:09:46.425–10:11:46.425 | 1,383,755 / 1,383,752 | 11,531.267 | 11,531.292 | 13 | 3 个客户端 `BindException`，0.0002%；筛查通过 |
| 25 | `activity-rps-25-20261026-181224-6709` | 10:14:08.505–10:16:08.505 | 1,426,886 / 1,426,884 | 11,890.700 | 11,890.717 | 13 | 2 个客户端 `BindException`，0.0001%；筛查通过 |
| 25 | `activity-rps-25-20261026-181628-5633` | 10:18:12.687–10:20:12.687 | 1,449,566 / 1,449,562 | 12,079.683 | 12,079.717 | 11 | 4 个客户端 `BindException`，0.0003%；筛查通过 |

25 线程三轮平均成功 RPS 为 **11,833.883**，中位数 **11,890.700**，实测范围 **11,531.267–12,079.683**。成功 P99 为 **11–13 ms**，中位数 **13 ms**。三轮合计 4,260,207 个样本，9 个失败均为 JMeter 客户端的非 HTTP `java.net.BindException`，没有 HTTP 状态或业务断言失败；合并错误率约 **0.00021%**。

单线程 30 秒预检另有 39,895 个成功样本、1,329.833 成功 RPS、2 ms P99。该预检采用 1 秒爬坡和 2 秒预热，**不纳入正式档位对比**。

## 稳定性与限制

测试前声明的筛查规则为：正式窗口至少 1,000 个样本、错误率不高于 1%、没有连续两个 10 秒桶低于非空桶成功 RPS 中位数的 50%、末尾三桶 P99 中位数不超过开头三桶的两倍。规则是本次技术筛查条件，不是业务 SLA。10 线程和 25 线程三轮都通过；报告不声称系统峰值或部署环境容量极限。

25 线程较 10 线程的单轮成功 RPS 约提高 25%，P99 从 2 ms 增至 13 ms。测试期间 JMeter 与后端同机；一次 5.22 秒的 Java 进程采样显示后端与 JMeter 合计约占 16 个逻辑处理器的 83.8%。因此停止继续升到 50 线程及以上，25 线程是本机条件下的最高已验证稳定档位。Docker MySQL 与 Redis 末端 CPU 快照低于 3%；RabbitMQ 管理容器的瞬时 CPU 快照波动较大，而详情 GET 不访问 RabbitMQ。客户端绑定异常数量极少，但应在独立压测机复测时继续关注。

测试机与服务端同机，结果受 JMeter 客户端和主机并发资源影响。不得把此结果写成生产服务器极限；也未测试多活动混合读取、缓存关闭对照或 50+ 并发。

## 复核证据

每个批次目录包含 gzip 无损压缩的原始 CSV JTL、JMeter 日志、JSON 摘要和不含凭据的运行信息，全部位于本机忽略目录，不提交 Git：

- `.local/jmeter/rps-p99/activity-rps-10-20261026-180341-9645/`
- `.local/jmeter/rps-p99/activity-rps-25-20261026-180802-4498/`
- `.local/jmeter/rps-p99/activity-rps-25-20261026-181224-6709/`
- `.local/jmeter/rps-p99/activity-rps-25-20261026-181628-5633/`

每轮命令均为：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/jmeter/run-activity-rps.ps1 `
  -Threads <10 或 25> -RampUpSeconds 30 -WarmupSeconds 60 -MeasureSeconds 120
```

摘要由 `scripts/jmeter/summarize-activity-rps.py` 从压缩 JTL 或 CSV JTL 计算；分桶结果、失败码、正式窗口边界及稳定性筛查明细在各批次 `summary.json`。每轮 Redis keyspace hit/miss 是隔离 Redis 的全库差值，不作为活动缓存命中率；测后单独读取确认了活动详情 Redis L2 键存在，TTL 为 130 秒，该确认请求不属于压测窗口。
