# Redis 503 根因定位与验证

日期：2026-10-27。报告脱敏；原始诊断数据留在 Git 忽略目录 `.local/jmeter/`。

**结论：本次隔离复现的 7 个 503 全是 Redis 命令超时；同一时间 Redis 服务端明确记录了 529–611ms 的 `appendfsync always` 延迟，根因机制高度指向同步 AOF fsync 阻塞 Redis 响应。证据等级为“强关联，机制已证实、底层存储成因未证实”；没有实施生产修复。**

## 1. Git 和实际运行环境

- 分支 `main`，测试基线 commit：`c5d4901cf2de27b6f10a4aab45fb4e3e7e089841`。开始时工作区已有其他未提交改动；没有覆盖、清除或推送它们。
- Windows 11 Pro 10.0.26200，i5-13400F（10 核/16 线程）、31.82 GiB 内存；Java 21.0.10；Docker Server 29.5.2（Linux Docker Desktop）；Spring Boot 3.5.16、Lettuce 6.6.0、MySQL 8.4.11、Redis 7.4.11、RabbitMQ 4.1。
- 复现仅连接 `ticketing-jmeter`：MySQL `127.0.0.1:13307/ticketingjmeter`、Redis `127.0.0.1:16379`、RabbitMQ `127.0.0.1:15673/15674`、诊断应用 `127.0.0.1:18080/18081`。容器挂载为本轮独立卷 `*_capacity_34bb63d781b6`。没有读取业务 `.env`、连接业务库/Redis/MQ、修改或删除业务卷；业务三个 Docker 容器仍健康。隔离容器和卷保留以供复核。
- 实际运行 JAR SHA-256：`2fe35e007f5bf8ccdbce7bd5feb0bea7d84283babd5f515370d01f3df91d2a38`；实际计划 `scripts/jmeter/purchase-post-only.jmx` SHA-256：`476e2296156ac8ba886b4fb3c4028d0abee9c4d5d0b709cdafc79a22ceadc717`。构建后当前本地 JAR 已包含额外的失败事件保留改进，SHA-256 为 `d471ae71cb5af73ba2068939504490d5f82bb1a4219b9de63d684748b5e40d8c`；它未用于本轮压力复现。
- 运行时诊断端点读取到 Redis command timeout 与 connect timeout 均为 **500ms**，没有本机覆盖。Redis 实际配置为 `appendonly yes`、`appendfsync always`。只在独立 Redis 上临时将 latency monitor 阈值从 0 设为 5ms 以采集事件；结束后已恢复为 0，AOF 设置始终未改。
- 受控窗口：2026-10-27 21:18:57.677–21:19:27.677（北京时间），16 JMeter 线程/16 在途、30 秒闭环档、2 个消费者、Hikari=10、发布窗口=32，最多 600 个新账号请求。不是性能验收档。

## 2. HTTP 503 分类

历史 100 在途 A/B 共 10 次：A 组 4 次（2 `PURCHASE_ADMISSION_UNAVAILABLE`、2 `RATE_LIMIT_UNAVAILABLE`），B 组 6 次（各 3 次）。当前 16 在途诊断复现另有 7 次：3 次购票入口错误、4 次安全限流错误。当前两种错误的计时器均记录为 `RedisCommandTimeoutException`，分别对应 3 次购票预扣 Lua 超时、4 次限流 Lua 超时；没有 `-3` 门禁返回。

| 错误码 | 精确触发链 | 本轮结果 |
|---|---|---|
| `RATE_LIMIT_UNAVAILABLE` | `RequestRateLimitFilter` → `RedisRateLimiter.order/evaluate` → 安全限流 Lua；Redis `DataAccessException` 被 fail-closed 映射为 503。正常限流是 429。 | 历史 5 次均属于这条调用链；本轮 4 次均为限流 `EVAL` 超时。 |
| `PURCHASE_ADMISSION_UNAVAILABLE` | `PurchaseAdmission.submit` → `RedisStockReservationService.reserve` 的 Redis 读取、预扣 Lua 或预扣后读回异常被映射为 503。 | 历史 5 次可定位到 `reserve`，当时未细分内部 Redis 步骤；本轮 3 次均为 `purchase-reserve.lua` 超时。 |
| `PURCHASE_PUBLISH_UNCONFIRMED` | Redis 预扣之后的 RabbitMQ 发布确认失败。 | 历史和本轮目标批次均为 0；没有证据指向发布槽位。 |
| `PURCHASE_QUEUE_FULL` | 预扣 Lua 返回 `-2`，应用在途容量门禁。 | 0；不是 RabbitMQ 队列满。 |

历史 10 次的单条 Redis 命令/异常类型没有在当时采集，无法补造逐事件根因。历史日志至少有一次限流路径 `QueryTimeoutException`；JTL 中 10 次 HTTP 耗时为 508–741ms，与 500ms 命令超时吻合，但旧运行没有按命令阶段计时。

## 3. 故障逐事件时间线

历史 A/B（相对各自 60 秒窗口起点）：

| 批次 | 起始时间偏移 | 结果 | 最终订单 |
|---|---:|---|---|
| A | +4.979s、+4.982s | 2 次 `PURCHASE_ADMISSION_UNAVAILABLE`，559/556ms | 两笔均在最终数据库事实中成功一次 |
| A | +5.030s、+5.055s | 2 次 `RATE_LIMIT_UNAVAILABLE`，519/508ms | 均未建单 |
| B | +7.415s（3 次） | 3 次 `PURCHASE_ADMISSION_UNAVAILABLE`，553/553/552ms | 三笔均在最终数据库事实中成功一次 |
| B | +12.881s、+13.006s、+13.031s | 3 次 `RATE_LIMIT_UNAVAILABLE`，741/615/589ms | 均未建单 |

当前 7 次复现（JTL 时间是请求开始时间）：

| 请求开始 | JTL 耗时 / 结果 | Redis 阶段证据 |
|---|---|---|
| 21:19:11.147、21:19:11.149 | 536/534ms，购票入口 503 | `purchase-reserve.lua` 超时共 3 次；该早期事件记录被成功结算事件挤出普通 256 条环形区，但计数与 3 个购票入口 503 一一对应。 |
| 21:19:11.210、21:19:11.211、21:19:11.210 | 524/524/526ms，限流 503 | `security.rateLimit.eval` 超时共 4 次；早期逐事件记录同样被覆盖，计数与限流 503 一一对应。 |
| 21:19:16.378 | 952ms，限流 503 | 21:19:17.323 诊断到 `security.rateLimit.eval`，根异常 `RedisCommandTimeoutException`，阶段耗时 512ms。 |
| 21:19:16.676 | 618ms，购票入口 503 | 21:19:17.292 诊断到 `purchase.reserve.lua`，根异常 `RedisCommandTimeoutException`，阶段耗时 509ms；预扣结果尚未被客户端确认。 |

Redis `LATENCY LATEST/HISTORY` 在 21:19:11 记录 `aof-fsync-always` **611ms**，21:19:17 记录 **529ms**。同窗 Windows 到 Redis 映射端口的独立 PING 分别为 439.118ms（21:19:11.348）和 406.517ms（21:19:16.924）。Redis 服务端累计 `LATENCYSTATS` 的 EVALSHA P99 约 0.664ms、EVAL P99 约 1.196ms、PING P99 约 0.003ms；SLOWLOG 长度为 2，没有捕获长命令。同期 `INFO persistence` 的 `aof_delayed_fsync=0`、`aof_pending_bio_fsync=0`、`aof_last_write_status=ok`，不能否定 `appendfsync always` 的同步 fsync 停顿。

## 4. 证据强度

- **直接证实**：当前 7 个 HTTP 503 的 JTL 错误码为 3+4；诊断阶段计数为 3 次预扣 Lua 超时、4 次限流 Lua 超时，根异常是 `RedisCommandTimeoutException`。运行时超时确为 500ms。Redis 服务端 latency monitor 直接记录两次超过 500ms 的 `aof-fsync-always` 延迟。
- **强关联**：两次 Redis 端 AOF 延迟与 503 时间窗重合，映射端口 PING 同期升至 439/406ms；Redis 命令执行 P99 本身远低于 500ms。当前证据符合“同步持久化等待阻塞服务响应”，不像 Lua 业务计算过慢。
- **历史批次证实范围有限**：10 个旧 503 可按 HTTP 错误码映射到入口限流或 `reserve`；5 个入口错误按原幂等键最终成功，5 个限流错误未建单。旧批次缺少逐 Redis 命令诊断和服务端 latency 采样，不能把当前 7 次阶段结论伪装成旧批次的逐次原始证据。
- **已排除/未观察**：当前 7 次没有 `-3`、`PURCHASE_QUEUE_FULL`、RabbitMQ 发布确认错误；`ready=1`，未完成库存账本最终归零。资源 34 个样本最大间隔 2.469 秒、无 unsafe 记录；主机 CPU 峰值 58.74%、可用内存最低 15.08GiB；JMeter 22 个样本工作集峰值 248MiB、CPU 峰值约 16% 主机；后端工作集峰值 450MiB、CPU 峰值约 20%；后端最大 GC pause 35.8ms，JMeter 18.3ms，无 OOM。MQ ready 峰值 13、unacked 峰值 2，最终均为 0；Redis pending 峰值 15，最终为 0。
- **仍未知**：没有与故障同刻的 Redis 容器 loopback PING、Windows/WSL/Docker 虚拟磁盘写延迟或块设备 fsync 延迟，也没有 Lettuce Netty event-loop stall 采样。因此物理存储抖动的来源、PING 总耗时中服务端/容器转发/主机调度各自所占比例未知。

## 5. 明确根因定位

已定位到可复现的故障机制：Redis 开启 `appendfsync always` 时，写命令的同步 AOF fsync 偶发耗时 529–611ms；在这个窗口内，购票预扣或安全限流写 Lua 的客户端等待超过实际 500ms 超时，分别转成两种 HTTP 503。服务端延迟事件是 AOF fsync，而不是慢日志中的某条 Lua 执行 500ms。高置信度指向 Redis 所在 Docker/WSL2 存储路径的偶发写盘停顿；但没有块设备延迟采样，不能断言是 SSD、WSL 虚拟盘或 Docker 调度中的哪一层。

历史 A 组 547ms、B 组 422ms PING 是 Windows 客户端到映射端口的端到端时长，不等于 Redis 命令执行时间。历史运行没有同期服务端 AOF latency 数据；结合本次复现只能说 AOF 停顿很可能有贡献，不能精确拆分历史 547ms 的各层耗时。

## 6. 最小修复及其理由

- 增加默认关闭、仅隔离诊断模式可用的 Redis 阶段计时/低基数指标、有限脱敏事件、失败后 `-3` 快照、Lettuce 命令指标，以及 Windows→Redis PING 和 Redis INFO/LATENCY 采样。诊断入口绑定本机 18081 并校验独立库/端口。
- 本次发现普通事件环会被高频成功结算覆盖，因此新增容量 128 的独立失败事件环；失败事件不记录异常消息、Redis key、用户/订单 ID 或幂等键。
- **没有代码级生产修复**：不提高 500ms 超时、不扩大连接池、不关闭 AOF、不改限流、预扣 Lua、事务或 RabbitMQ 确认。它们不是已证实的根因修复，且会改变安全/持久化语义。AOF 写盘路径本身需要独立环境定位后，才能制定保留 `always` 的部署修复。

## 7. 修复前后验证

没有生产修复，因此没有可称为“修复后”的性能数字。当前诊断档原始结果为 600 次 POST、593 个 202、7 个 503（3 个入口、4 个限流）；摘要原始值订单 TPS 19.83、POST 至结算 P99 1045ms、客户端观察 P99 1130ms、HTTP P99 521ms。由于 503 且 600 个账号预算耗尽，运行 `valid=false`，这些值**不是稳定性能结果，不用于宣称 TPS 或 P99 改善**。没有升到 32/100 在途，也没有做高负载复测。

该档最终只读审计通过：3 个入口 503 请求均按原幂等键最终建成唯一成功订单；4 个限流 503 没有建单。重复订单、孤儿、关联错误和额度错误均为 0；MySQL `locked=616/sold=0` 与订单明细一致、Redis 可售 9384、pending/recovery=0、`ready=1`；MQ published/ack/delivered 均为 615，ready/unacked/dead-letter 均为 0。616 锁定量含正式窗口以外的 20 笔预热订单。历史两轮各自的库存审计也通过：A locked=2052、B locked=4230，均与 Redis 可售余额守恒。

## 8. 代码与测试清单

- `mvn -f backend/pom.xml clean verify`：109 项测试通过，0 失败、0 错误。
- `python -B scripts/jmeter/test_redis_probe.py`：3 项通过；`python -B scripts/jmeter/test_safety_rules.py`：3 项通过；诊断 Python 文件语法检查通过。
- 已在专用隔离资源实际执行 16 在途/30 秒/600 POST 的诊断负载，并完成数据库、库存、Redis 和 MQ 排空审计；该负载有失败，不能算性能验收。
- 未执行 100 在途或修复后测试；没有对业务库或业务 Redis/MQ 发压、改配置或注入数据。

## 9. 遗留问题与下一步

先在新的隔离批次增加容器内 loopback PING、宿主机/WSL 虚拟磁盘 fsync 延迟和故障时间线的高精度采样；再以独立、可丢弃 Redis 做单变量 `appendfsync everysec` 短诊断对照（仅验证 fsync 关联，明确不作为生产修复），恢复/保留生产语义为 `always`。未确认存储层修复且低档无 503 前，不重新做 SKU 锁 SQL A/B，不升到 100 并发；库存分桶不能解决本次 Redis 超时。

## 最重要问题逐条回答

1. **旧 10 次分别在哪个阶段？** 5 次 `RATE_LIMIT_UNAVAILABLE` 精确在 `RequestRateLimitFilter → RedisRateLimiter.evaluate → 限流 Lua`；5 次 `PURCHASE_ADMISSION_UNAVAILABLE` 精确在 `PurchaseAdmission.submit → RedisStockReservationService.reserve`，旧数据没有保存其中每次的 HGET/Lua/读回细分。本轮复现补证了同类 3 次入口 503 位于 `purchase-reserve.lua`，另 4 次限流 503 位于限流 `EVAL`。
2. **哪些是实际 Redis 异常，哪些是 ready 门禁？** 本轮 7 次均是 `RedisCommandTimeoutException`，`-3` 计数为 0。旧 10 次没有逐条异常类；5 次购票入口 503 均最终唯一建单，排除了这些请求在预扣前因 `ready`/账本门禁直接拒绝；其他旧请求的底层异常类型不能逐条还原。
3. **547ms PING 是服务器还是网络？** 它只证明 Windows 到 Redis 映射端口的端到端延迟，旧轮未采服务端延迟，无法拆分。新复现中同时观察到服务端 AOF 611/529ms 与端口 PING 439/406ms，强烈支持 Redis 服务端持久化停顿参与，但不能把旧 547ms 全归到服务器。
4. **是否有 AOF/fsync 同期证据？** 有：实际 `appendfsync always`，Redis latency monitor 在两波错误同秒记录 611ms 和 529ms 的 `aof-fsync-always`。
5. **已预扣却返回 503 是否正确恢复？** 是。历史 5 笔和本轮 3 笔入口错误最终均按原幂等键各建成一个订单；没有重复/孤儿，Redis recovery/pending 与 MQ 队列排空，库存和额度审计通过。
6. **是否修复、复测后 503 是否消失？** 尚未修复，也没有修复后复测。现有证据已定位到高置信度故障机制，但物理存储层来源和保留 AOF `always` 的部署修复仍待隔离单变量验证；因此不能宣称 503 已解决。
