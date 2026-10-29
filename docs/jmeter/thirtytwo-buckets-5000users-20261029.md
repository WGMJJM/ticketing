# 32桶与16桶单轮对照

同一台电脑、相同闭环方法；除32桶支持及其必要的V17约束扩展外，业务事务、SQL、Lua、持久化和负载参数不变。对照采用上轮全新物理环境的16桶结果，不混用旧库119.10 TPS。

| 参数 | 本轮 |
|---|---:|
| 全新普通用户 / 启动检查 | 5000 / 3 |
| JMeter线程 / 在途 / Redis容量 | 500 / 500 / 500 |
| 消费者 / prefetch / Hikari | 72 / 1 / 72 |
| 空SKU库存 / 桶数 | 20000 / 32 |
| 预热稳定后不断流测量 | 36.213秒 + 30秒 |
| 每用户预算 / 总请求预算 | 最多4笔不同幂等键 / 19988 |

V16起已移除发布确认窗口，本轮仍为null。预登录不计入测试窗口，无固定QPS/TPS，保留约2秒资源采样和实际停发保护。

| 指标 | 上轮16桶 | 本轮32桶 |
|---|---:|---:|
| 成功订单TPS | 264.07 | **347.13（+31.46%）** |
| 实际HTTP QPS | 263.30 | 346.63 |
| HTTP响应P99 | 545ms | 320ms |
| POST至建单结算P99 | 2547ms | 1714ms |

| 正式窗口 | 0–5秒 | 5–10秒 | 10–15秒 | 15–20秒 | 20–25秒 | 25–30秒 |
|---|---:|---:|---:|---:|---:|---:|
| 成功订单数 | 1759 | 1720 | 1655 | 1800 | 1777 | 1703 |
| TPS | 351.8 | 344.0 | 331.0 | 360.0 | 355.4 | 340.6 |

正式窗口10414笔建单。全负载18923笔加3笔启动检查全部成功，错误0，用户最多4笔、无到期订单；全轮与审计有效，预算未耗尽。32个桶均实际建单，最高31号桶597笔；库存20000=锁定18926+可售1074，已售0，Redis与MySQL一致，各项库存/分配/额度审计零偏差。结束MQ、Redis在途与恢复索引均归零，后端正常关闭，会话文件已删除。

CPU峰值92.87%、正式均值82.27%，物理内存最少余16.00GiB；MQ ready峰值383、unacked峰值72，结束均0，资源保护及停止加压标志均未触发。仅本轮对照，不代表机器极限或多轮稳定增幅。

实现修改涵盖StockBucketRouter路由、TicketInventory建单准入、StockSnapshotService库存发布及TicketService管理端启售；V17扩展票档桶数、桶号和切换凭证三个CHECK约束。升序跨桶回退、历史allocation履约、幂等查询与成功回读均保留，业务默认值及原业务SKU不改。115项普通测试、6项真实中间件用例通过，覆盖最高桶、跨桶回退、重复消费、支付/取消/退款/超时释放、扩容/重新启售与32桶满库存一致性。

旧fresh_91de6a57de10三专用卷、三个容器和专用网络已删除，新建fresh32_57796e11de09三卷、随机凭据和新网络；MySQL UUID已改变，初始化前表0、Redis键0。镜像ID、MySQL提交/binlog持久化和Redis AOF always与对照相同；业务容器/卷未动，没有备份。V17只应用于新隔离环境，业务库未迁移。当前配置为`.local/jmeter/thirtytwo-buckets-20261029/runtime`，旧runtime已停用。

当前证据`.local/jmeter/thirtytwo-buckets-20261029/c72-p72-w500-e51c87/`及父目录环境、源码和最终核验JSON；上轮16桶证据为`fresh-volumes-20261029/c72-p72-w500-07e8d4`。

```powershell
python -u -B scripts/jmeter/diagnose-purchase.py --runtime .local/jmeter/thirtytwo-buckets-20261029/runtime --java D:/JDK17/bin/java.exe --jmeter-jar C:/Users/28298/AppData/Local/Temp/apache-jmeter-5.6.3/bin/ApacheJMeter.jar --concurrency 72 --pool 72 --threads 500 --inflight 500 --capacity 500 --allow-capacity-fill --max-posts 19988 --seconds 30 --repeat-per-user 4 --startup-checks 3 --bucket-count 32
```

命令仅记录本轮事实；已消费本轮账号和库存，后续先按授权重建干净环境，不能直接复用旧到期订单。
