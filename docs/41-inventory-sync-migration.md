# V16 库存同步迁移

V16 删除旧库存 Outbox 表。历史库必须停写并完成以下检查，不能让新旧应用同时运行。先备份 MySQL 和 Redis 持久数据；失败时保留现场，不自动 repair、清库或猜测重建账本。

1. 停止新购票和其他库存写入，保留旧版消费者、恢复任务及库存发布器直至申请、预扣和库存事件排空。核对库存、订单、额度、候补及分桶审计。
2. 停止所有旧版应用实例，再确认普通购票队列、库存通知队列及对应死信队列的 ready/unacked 均为0，消费者为0；不能只检查 Outbox 的 SENT 状态。检查 Redis 命名空间的 `recovery` ZSET 和 `pending` 总计数均为0，检查每个已初始化 SKU 的 Redis 账本存在且与数据库一致。检查 MySQL `purchase_request` 没有未决行。
3. 只读检查旧库存事件及未决申请；以下两项结果均须为0。未发送或未知类型事件必须在旧版中核实和处理，不能手动删除事件以绕过检查。应用的只读预检也会在这些情况阻断迁移。

   ```sql
   SELECT COUNT(*) AS invalid_or_unfinished_events FROM outbox_event
   WHERE status <> 'SENT' OR aggregate_type <> 'TICKET_SKU'
      OR event_type <> 'WAITLIST_DISPATCH_REQUESTED' OR id >= 9223372036854775807;
   SELECT COUNT(*) AS unresolved_requests FROM purchase_request WHERE status = 'QUEUED';
   ```
4. 单次设置 `INVENTORY_SYNC_MIGRATION_APPROVED=true` 启动新版。V16 对每个已有 SKU 记录旧库存事件最大序号加一，再移除 Outbox 表；后台在 SKU 锁内暂停入口、等待未完成预扣归零，然后以 MySQL 绝对余额和新版本安全同步 Redis。已有账本丢失或损坏会停卖，不会自动初始化。
5. 核对历史用户、订单、支付、退款、候补、票券和分桶 allocation，确认所有已初始化 SKU 的 Redis `inventoryVersion`、可售余额、销售资格和 MySQL 一致，普通购票队列及恢复索引正常。移除一次性批准变量后再放写。

旧库存通知交换机、队列及绑定不再由新版声明；仅在确认旧应用已停止且队列、死信均已排空后，由环境管理员删除其遗留 RabbitMQ 对象。普通购票队列和死信队列仍保留。

数据库预检无法替代对所有应用实例、RabbitMQ 和 Redis 的停写与排空检查。V16 不自动修改历史分桶数据，历史订单继续依照原 allocation 支付、取消和退款。
