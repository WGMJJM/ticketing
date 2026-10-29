# 干净隔离库 5000 用户购票测试

2026-10-29，按用户要求直接清空旧隔离数据，创建5000名新用户，3笔启动检查成功后，只跑一轮连续预热及正式30秒。业务库未启动、未修改；没有修改交易逻辑、限购、安全机制、持久化参数或业务默认配置。

| 参数 | 实际设置 |
|---|---:|
| 库存桶 | 16 |
| JMeter线程 / 在途 | 500 / 500 |
| 隔离Redis容量 | 500 |
| 消费者 / prefetch | 72 / 1 |
| Hikari连接数 | 72 |
| 新建并预登录用户 / 启动检查用户 | 5000 / 3 |
| 连续预热 / 正式窗口 | 31.190秒 / 30秒 |
| 发压 | 闭环，不固定RPS |

正式窗口为北京时间14:08:16.838至14:08:46.838，按校准后的MySQL创建时间统计最终成功的唯一订单，左闭右开；预热及窗口后的排空不计入下表。

| 正式时间段 | 成功订单 | TPS |
|---|---:|---:|
| 0–5秒 | 1316 | **263.20** |
| 5–10秒 | 1321 | **264.20** |
| 10–15秒 | 1337 | **267.40** |
| 15–20秒 | 1285 | **257.00** |
| 20–25秒 | 1259 | **251.80** |
| 25–30秒 | 1293 | **258.60** |
| 完整30秒 | 7811 | **260.37** |

HTTP响应QPS260.03，成功HTTP P99为415ms；POST至建单并完成Redis结算P99为2387ms。HTTP与订单按各自发生时间计数，窗口边界数量可有少量差异。预热/正式共13450笔，另3笔启动检查，全部成功，无网络错误、重复幂等键或未知结果，账号预算未耗尽。MQ ready/unacked、Redis pending/recovery最终均为0；订单关联、额度、16桶和Redis/MySQL库存审计通过，wholeRunValid=true。CPU全程峰值92.71%，正式采样峰值87.50%，最低可用内存15.38GiB；未达到持续CPU阈值或其他熔断阈值，未追加更高负载。隔离JVM正常关闭、退出码0。本轮只验证该档位，不代表硬件极限。

## 排除旧数据干扰

先验证ticketing-jmeter容器标签、专用卷、端口和随机guard，确认旧请求排空、后端停写及无活动事务，再删除并重建唯一的13307/ticketingjmeter schema。旧库有60001个账号、67089笔订单及申请；专用Redis删除134191个旧键，仅保留环境guard，RabbitMQ专用队列清空内容、保留拓扑。没有新增备份、删除业务卷或使用全局清理。

Flyway在空库正常创建V1–V16，新夹具为5000名普通用户加1名引导管理员、1活动/1场次/1个空16桶SKU，库存20000；发压前订单、申请、额度均为0。原过期扫描和恢复任务保持启用，本轮过期UPDATE的performance_schema计数增量为0（4200→4200，累计统计不因DROP schema归零）；结束时到期订单0。旧订单没有参与本轮建单、过期处理或排队。

5000名用户一次预登录，3名仅用于启动检查，其余4997名每人预算最多4个不同幂等请求，总预算19988，不突破真实场次限购4张。本轮实际每人最多3笔，5000名用户均参与；httpInterface中的duplicateAccounts表示同一合法用户发起后续新订单，不是重复幂等键，不按独立用户数计TPS。V16没有普通购票Publisher Confirm窗口。

## 复现与证据

仅在按上述要求清空、初始化后的新账号池执行：

```powershell
python -u -B scripts/jmeter/prepare-data.py --env-file .local/jmeter/redis-mysql-optimizations-20261028/runtime/compose.env --users 5000 --bucket-count 16 --orders-per-user 4
python -u -B scripts/jmeter/diagnose-purchase.py --runtime .local/jmeter/redis-mysql-optimizations-20261028/runtime --java D:/JDK17/bin/java.exe --jmeter-jar C:/Users/28298/AppData/Local/Temp/apache-jmeter-5.6.3/bin/ApacheJMeter.jar --concurrency 72 --pool 72 --threads 500 --inflight 500 --capacity 500 --allow-capacity-fill --max-posts 19988 --seconds 30 --repeat-per-user 4 --startup-checks 3
```

建数工具要求空业务表且仅有引导管理员，不能直接覆盖本轮已用数据。未自动扩大用户数或循环加压。修改仅涉及V16新夹具、有限多笔账号预算和JTL用户索引；Python/JMX语法及原23项轻量工具测试通过，3笔真实链路检查和本轮实际运行通过，未重复执行无关业务回归。

本地证据位于`.local/jmeter/redis-mysql-optimizations-20261028/`：`clean-reset-20261029/reset-receipt.json`、`clean-bootstrap-20261029/fresh-before-load.json`及`c72-p72-w500-ef6053/`下原始JTL、订单、参数/源码/JAR哈希、资源、指标、expiry-delta、fresh-data-audit、完整审计与正常关闭证明；临时会话文件已删除。历史数据库数据已删除，旧报告仅代表当时快照。后续干净对照必须重新确认没有旧到期订单、未决申请或积压，不能直接沿用已过期的本轮数据。
