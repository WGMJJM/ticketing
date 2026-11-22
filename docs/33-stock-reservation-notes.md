# 库存预扣与购票结果说明

## 受理与查询

1. `POST /api/v1/orders` 先经过 Session 鉴权、CSRF 和用户安全限流。随后 `PurchaseAdmission` 调用 `RedisStockReservationService`，由 `purchase-reserve.lua` 检查开售时间、可售余额、单笔数量、用户场次在途数量和系统在途容量。预扣成功时，在同一次 Lua 执行中扣减 Redis 可售数，记录 `RESERVED` 凭据和恢复索引。
2. 普通购票的幂等范围是用户、`CREATE_ORDER` 和 `Idempotency-Key`。同键同票档及数量沿用原 `requestId`，同键异参返回冲突。库存、容量等检查明确拒绝时，Lua 在写入预扣凭据前返回。
3. `PurchasePublisher` 直接尝试向 RabbitMQ 发送携带原 `requestId` 和 `skuId` 的持久消息，跳过 Publisher Confirm 等待。发送抛错时保留预扣并记录失败，由恢复任务补发。HTTP 202 的含义仅为 Redis 已受理，客户端显示 `QUEUED`。回放已知终态时返回 HTTP 200。
4. 本人可按 `requestId` 查询 `GET /api/v1/purchase-requests/{requestId}`，或携带原幂等键调用 `GET /api/v1/purchase-requests/lookup`。查询优先读 MySQL 已提交结果，再查 Redis。成功终态带 `orderId`，用户随后可查看订单并支付。

## 建单、结算与恢复

1. `PurchaseOrderWorker` 使用 Redis 预扣凭据，以 `requestId` 的唯一键和行锁协调 `purchase_request`。MySQL 的 `QUEUED` 行只是事务内占位，过期仲裁使用同一套锁。
2. 建单按账号、活动、场次、用户场次额度、SKU 的顺序加锁，必要时继续锁桶。MySQL 在同一事务内按条件预占库存和场次额度，写入订单、明细与 `SUCCEEDED` 终态。确定业务失败先回滚建单，再登记 `FAILED` 或 `EXPIRED`。死锁等暂时故障保留预扣，交由重试或恢复。
3. SQL 提交后，`purchase-settle.lua` 将成功凭据转为 `CONSUMED`，失败或过期凭据转为 `RELEASED` 并返还预扣。重复消费和结算沿用原终态，库存只扣减或释放一次。
4. `PurchaseRecoveryJob` 扫描 Redis 恢复索引。已有 MySQL 终态就补结算，仍有效且无终态就重发原申请，超过购票截止时间则先执行 MySQL 过期仲裁。死信是排查线索，返票以 MySQL 终态为准。已初始化的库存账本或有效凭据丢失时，暂停对应 SKU 并核对。

## 库存变更和候补

- 取消、订单过期、退款、销售资格和库存配置等需要同步的变化，与 `inventory_sync_version` 递增同事务。普通建单已通过 Redis 预扣反映余额，失败申请依原凭据直接结算。
- `PurchaseRecoveryJob` 分批比较 MySQL 版本与 Redis `inventoryVersion`，并在无在途预扣时核对余额。需要同步时，`StockSnapshotService` 在 SKU 锁内暂停新受理，等当前预扣结算完毕后再发布 MySQL 的绝对可售余额。已配置的 4、8、16、32 桶票档以所有桶的权威余额为准。
- `WaitlistCompensation` 周期扫描同一 `SINGLE` SKU 的队首。等待时不占库存或额度。调度器选定队首并记录尝试后，通过同一个 `PurchaseAdmission`、Redis 预扣和 RabbitMQ 消费者建单。未决队首挡住后续候补，既有普通预扣继续结算。

V14 移除了 MySQL 购票受理阶段的容量表和购票 Outbox 记录。V16 移除了库存 Outbox 表及库存通知队列的应用配置。整体协作见[当前架构](01-architecture.md)，历史库升级见[V16 切换检查](41-inventory-sync-migration.md)。
