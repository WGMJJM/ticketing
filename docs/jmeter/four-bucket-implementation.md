# 四桶库存实现与功能验收

2026-10-28，基线 `main c5d4901cf2de27b6f10a4aab45fb4e3e7e089841`。实现和验证均使用本地隔离环境，不操作业务数据库。开始时已有Redis 503诊断等未提交变更，在其上完成本轮开发；用户随后要求将完成的源码、脚本和报告按功能分批提交并推送到原仓库main。

## 实现前差异与本轮边界

原代码虽有 V10 桶表及历史订单生命周期，但 `TicketInventory.reserve` 拒绝 BUCKETED，所有交易对主 SKU `FOR UPDATE`，新订单不写归属，快照无条件禁售 BUCKETED。本轮改通四桶普通新购票；SINGLE 路径保留主 SKU 排他锁；历史其他桶数的订单仍沿原归属履约，新销售仅支持四桶。

分桶候补本轮明确不开放：入队资格拒绝 BUCKETED，新候补建单也显式拒绝；SINGLE FIFO/优先门禁与已分配订单生命周期保留。生产默认消费者数不改，隔离 RabbitMQ 回归使用 4 消费者、prefetch=1；100 买家功能测试使用 4 个工作线程执行真实生产 Worker，不是吞吐测试。

## 调用链及锁序

`HTTP → 原 Session/CSRF/限流 → Redis Lua 预扣 → RabbitMQ 持久消息及 Confirm → PurchaseOrderWorker → TradeResourceLocker → OrderReservation → TicketInventory → 订单/明细/归属/申请终态提交 → Redis settle`。

| 路径 | 事务内资源顺序 |
|---|---|
| 普通 SINGLE | 原 requestId 协调 → 账号 S → 活动 S → 场次 S → 用户场次额度 X → SKU X → 建单及终态 |
| 普通 BUCKETED 快速路径 | 原 requestId 协调 → 账号 S → 活动 S → 场次 S → 用户场次额度 X → SKU 配置 S → 目标桶 X → 建单/明细/归属/终态 |
| BUCKETED 跨桶路径 | 先完整回滚快速路径，再重新获取以上资源，桶 X 严格按 0→1→2→3，满足数量即停止 |
| 分桶支付/取消/过期/退款 | 原账号及父资源/按需额度 → SKU S → 原归属桶升序 X → 订单 X → 原候补/电子票协作 |
| 管理 | 原操作人锁 → 活动 S → 场次 X → SKU X → 按需升序桶 X |
| Redis 绝对余额快照 | 活动 S → 场次 S → SKU X，与所有普通分桶事务的 SKU S 互斥 |

SKU 模式在父资源受保护后重新读取，再一次性选择 S 或 X，不做 S→X 升级。普通分桶建单没有 SKU 主行 UPDATE，也没有全局分布式锁或串行任务队列。共享 SKU S 保护配置，真正互斥资源是 `(sku_id,bucket_no)` 桶主键行的 X 锁。

## 选桶、跨桶和失败语义

真实原 `current.id()` 贯通 Worker → Reservation → Inventory，`floorMod(requestId,4)` 决定初始桶。常见路径只对这一桶执行参数化条件 UPDATE：

```sql
UPDATE ticket_stock_bucket SET locked_stock=locked_stock+?
WHERE sku_id=? AND bucket_no=?
  AND CAST(total_stock AS SIGNED)-locked_stock-sold_stock>=?;
```

影响零行仅触发内部 `BucketReservationRetry`，不返回售罄；即使失败 UPDATE 留下行锁，也先回滚整笔事务后再升序跨桶，不带着较大编号锁回绕。跨桶逐个 `SELECT ... FOR UPDATE` 后以当前可用量分配，全部数量满足才能提交。权威总量不足而 Redis 已预扣属于矛盾：回滚、暂停 SKU、保留原申请交给核对/恢复，不登记虚假的无票终态、不擅自返票。

销售配置/窗口校验由 `TicketSale.windowStatusAt` 表达，分桶库存最终由锁后条件 SQL 仲裁，不用汇总展示快照的 SOLD_OUT 拒绝已预扣申请。未构造虚假库存数字绕过资格检查。

明确的悲观锁/死锁/锁等待失败最多 3 次尝试，退避 5–15ms、20–50ms，均在整个事务结束后等待。快速→跨桶回退只发生一次，不消耗瞬时故障预算。耗尽向 MQ/原恢复入口抛出技术异常，保持 Redis 预扣。现有外部事务调用者不在持锁事务中内部重试，异常交还其外层回滚；正式 MQ/恢复入口在事务外调用 Worker。

## 表结构与写 SQL

直接复用 V10 的 `ticket_stock_bucket`、`order_stock_allocation`、`stock_strategy_switch` 和 `ticket_stock_balance`，未新增迁移、未改 V1–V15。四桶编号 0–3、总量分配余数给前面的桶（401 为 101/100/100/100）。主 SKU BUCKETED 的 locked/sold 保持 0。

| 写入 | 热路径次数/对象 |
|---|---|
| request 协调/锁定、额度条件占用 | 沿用原 request 主键及用户场次唯一行，不共享所有买家的额度行 |
| 单桶预占 | 1 次联合主键条件 UPDATE，锁一个桶 |
| 跨桶预占 | 回退事务中最多 4 次升序锁定读及 4 次桶条件 UPDATE；不提交半笔预占 |
| 订单及明细 | 沿用各 1 次 INSERT |
| 归属 | 订单和明细 INSERT 之后，每个实际分配桶 1 次 INSERT，合计数量等于明细数量；同一事务提交 |
| 申请成功 | 沿用条件截止 UPDATE 和终态回读 |
| 普通 SKU 主行/库存 Outbox | 新购票均不写；取消、过期、退款、库存配置变化仍写既有库存 Outbox |

资源定位、幂等查询、数据库时间、配置重读及非锁定余额汇总仍是实际 SELECT 成本，未宣称零 SQL 或无锁。快速失败那一整笔事务的 SQL 也算真实成本，不能在后续压测中剔除。

`OrderReservation` 先形成不可变分配清单，在订单和明细存在后写 allocation。支付/释放/退款按持久原桶升序处理，不重选桶、不改 allocation。管理扩缩容仍受场次 X 锁保护且不得低于占用量；没有添加在线切换策略/桶数接口。

## Redis 与库存 Outbox

快照在 SKU X 下校验完整桶编号、数量、总量及各桶非负守恒，再发布汇总可售余额；保持 `pause → pending=0 → absolute publish`，重放事件不累加库存。默认关闭诊断新增 `ticketing.diagnostics.inventory.pauses` 和 `ticketing.diagnostics.redis.duration{stage=inventory.paused}`，在本进程内关联 ready=0 到恢复 ready=1 的次数/时长；内部关联最多 1024 个 SKU，无 SKU 指标标签。进程重启或有界缓存淘汰的未完成停顿无法还原，不能冒充持久审计。

Redis 命令默认超时改为 `1s`，`.env.example` 同步；连接超时仍为 `500ms`。环境变量仍可覆盖。AOF always、MySQL 持久化、Rabbit Confirm、安全限流及默认消费者配置均保留。

## 功能验证与证据

独立 Docker 资源为 `ticketing-jmeter`，13307/16379/15673/15674；先验证容器项目/卷归属及既有随机 guard，再建立功能验收随机 schema。业务 `.env`、3307/ticketing 和业务卷未操作。测试失败现场按原验证器保留，成功仅清理本轮随机 schema，不删除 Docker 卷。

执行命令：

```powershell
python -B scripts/verification/verify_backend.py --label fourbucket_accept --tests StockBucketIT,StockBucketMigrationIT,AsyncPurchaseIT,UserSessionQuotaIT,WaitlistManagementIT,OrderManagementIT,OrderAftercareIT,OrderExpiryRecoveryIT
```

测试覆盖及判断标准：

- `StockBucketIT.completeWorkersUseIndependentBucketLocksAndRollbackUnblocksSameBucket`：完整 Worker A 在提交前持桶 0，B 使用桶 1 在 A 未释放时完成；C 使用桶 0 实际进入 `data_lock_waits`，A 回滚后 C 成功。A 持锁期间 `data_locks` 断言主 SKU PRIMARY 没有 X 记录锁；A 保留原预扣后再次通过同 requestId 回退成功。
- `fourCompleteTransactionsHoldFourDifferentBucketExclusiveLocksTogether`：4 个真实 Worker 同时停在提交前，直接查询 `performance_schema.data_locks`，四条桶 PRIMARY 记录同时存在 GRANTED X 锁，SKU PRIMARY 的 X 记录锁为 0，随后四笔全部提交成功。
- 100 个独立用户、真实 Redis 预扣、4 线程真实 Worker 并发，100 个成功订单，四桶均有 allocation；有限库存 4、8 个买家仅 4 个预扣成功，不超卖；全部账户额度及库存账本对账。
- 每桶 1、购买 3 的跨桶支付退款；初始桶耗尽后的升序回退；MySQL 库存矛盾整体回滚/暂停/保留预扣；申请终态写入故障整体回滚；重复消费/取消/支付/退款不重复移动库存。
- 3 次瞬时锁失败耗尽后数据库无半笔订单、Redis 申请保留；真实 MySQL 死锁/锁超时和原 requestId 恢复；停销售配置竞态、管理缩容、过期释放、已核销退款人工处理及停用账号场次善后。
- 实际 Confirm + 4 消费者队列的 BUCKETED 跨桶建单；SINGLE HTTP/CSRF、幂等、额度、候补及售后回归；实际 RedisProperties 断言命令 1s、连接 500ms。

老功能夹具曾在未同步 Redis 时直接改数据库、仍断言旧租约字段或候补同步建单；本轮更新这些测试夹具以匹配当前 Redis 预扣/异步协议。并发 HTTP 夹具先统一完成初始化，避免多个发送线程主动 pause 正在处理的 SKU；拒绝请求仍严格要求业务 409，不能以 503 代替通过。V10 专项迁移测试显式 target=10，保持其历史结构验证职责。

最终实测结果：

| 执行 | 普通/架构测试 | 真实中间件集成测试 | 结果 |
|---|---:|---:|---|
| `fourbucket_accept`（10:48:14 完成） | 110 | 99（8 个类，含 18 个分桶用例） | 0 失败、0 错误、0 跳过，BUILD SUCCESS |
| 最后加强 `fourbucket_locks`（10:49:46 完成） | 110 | 19 个分桶用例 | 0 失败、0 错误、0 跳过，BUILD SUCCESS |

最后一轮去掉了 100 买家用例在测试内额外补跑的逻辑，要求所有 Future 直接完成；并新增四桶同时持 X 锁证明。测试没有丢弃失败请求来获得成功结果。真实死锁、原事务锁等待超时、连接池耗尽、恢复及完整 SINGLE 回归已在前一轮验收集中通过。连接池故障断言按真实 JDBC 连接失败根因校验，而非硬性要求异常发生于某一个包装层；库存/额度/唯一订单恢复断言保留。

私有原始输出为 `.local/verification/fourbucket_accept.log`、`fourbucket_locks.log`，最终 JUnit XML 位于忽略的 `backend/target/`。既有失败试运行日志和失败迁移现场保留，未删卷；本轮成功验证器按 guard 只清理其随机 schema。Redis AOF 实际仍为 `yes/always`，Java 集成用例核验命令超时 1s、连接超时 500ms。`git diff --check` 通过，V1–V15 无差异；Python 探针/保护单测各 3 项通过，脚本语法检查通过。

不以功能测试耗时计算 TPS，不声称四桶提高某个百分比。后续可使用独立饱和压测提示词，在随机 guard 的新批次设置 BUCKETED=4、消费者=4、prefetch=1，并核验实际 Redis 命令超时后测试；本轮未运行饱和压测。

## 变更文件

- 实现：`TradeResourceLocker`、`TicketInventory`、`StockBucketMapper`、`OrderReservation`、`PurchaseOrderWorker`、`BucketReservationRetry`、`TicketSale`、`StockSnapshotService`、`TicketService`。
- 参数及诊断：`application.yml`、`.env.example`、`RedisCommandDiagnostics`、对应诊断测试、架构归属登记。
- 验证：`StockBucketIT`、`StockBucketMigrationIT`、`AsyncPurchaseIT`、`OrderManagementIT`、`OrderAftercareIT`、`UserSessionQuotaIT`；既有 `diagnose-purchase.py` 实际超时检查同步采用配置值（默认 1s）。
- 文档：本文件及 `docs/01-architecture.md`、根 `AGENTS.md` 最新四桶规则。

尚未部署或切换任何业务 SKU。回滚代码前必须先停止分桶新受理并排空；已产生 allocation 的订单不能删除归属或改为 SINGLE，只能使用兼容历史桶履约的版本继续支付/关闭/退款。没有新增 DDL，因此不存在自动执行降级迁移。
