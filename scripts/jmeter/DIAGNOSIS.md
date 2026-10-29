# 购票 TPS 安全诊断

仅在专用 MySQL、Redis、RabbitMQ 和随机 guard 环境使用 `diagnose-purchase.py`，不要加载业务 `.env`。初始化和复用方法见 [README](README.md)。已有 V15 测试环境必须先完成 [V16 切换检查](../../docs/41-inventory-sync-migration.md)。

```powershell
python -u -B scripts/jmeter/diagnose-purchase.py --runtime $runtime --java $java --jmeter-jar $jmeterJar --concurrency 12 --pool 10 --threads 100 --inflight 100 --capacity 200 --max-posts 7980 --seconds 30
```

正式窗口只发送购票 POST；每个线程等待上一个申请完成后继续，不设目标 RPS。至少连续预热30秒，最近三个5秒段稳定后不断流测量30秒。购票 202 表示 Redis 已受理，不代表消息已被 Broker 确认或订单已创建。成功受理 RPS、HTTP 延迟及成功订单 TPS 分开统计；完整建单延迟包含 MySQL 提交和 Redis 结算，不含支付。

`--fresh-bucketed-sku --bucket-count 4|8|16` 只在隔离库新增空票档，不切换原票档。账号需在窗口前预登录，容量、CPU、内存、监控、错误和持续积压保护保持开启。异常批次保留并标记无效，不以短时吞吐作为稳定上限。

收尾检查申请与订单关联、额度、分桶 allocation、Redis 可售余额与数据库库存同步版本，并等待购票队列和恢复索引排空。Redis 与 MySQL 不一致或账本缺失时停止并排查，不能通过覆盖账本继续测试。

历史分桶和发布确认参数仅描述当时版本，见 `docs/jmeter/` 对应报告；新版本已移除发布确认窗口参数。
