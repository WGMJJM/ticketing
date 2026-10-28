# 8桶均匀路由核心对照

2026-10-28，基线`2ef930f`。唯一业务改动为首次选桶使用`StockBucketRouter`的MurmurHash3 64-bit finalizer加掩码；正常路径仅整数运算。快速条件UPDATE、整笔回滚后升序跨桶、原requestId幂等、既有allocation履约保持原协议。

配置保持8桶、12消费者/prefetch1、Hikari10、100线程/100在途、capacity200、发布窗口64、8000登录用户。新建隔离SKU 6，连续预热31.254秒稳定后测30秒；Redis AOF always/命令1s、MySQL flushAtCommit=1/syncBinlog=1及MQ持久确认不变。旧值为历史8桶SKU 5，未额外重跑旧路由；不同时间/批次，仅作历史参考。

| 指标 | 旧路由 | 混合路由 |
|---|---:|---:|
| 成功订单TPS | 73.27 | **66.00（-9.9%）** |
| 完整建单P99（含排队、提交和Redis确认，不含支付） | 1.629秒 | **1.789秒（+9.8%）** |
| 最大桶占比 / 相对均值最大偏差 | 25.27% / 102.14% | **13.30% / 7.63%** |
| 正式唯一订单 / 正式成功POST | 2198 / 2199 | 1980 / 1983 |
| 错误、503、槽位不足 / 完整验收 | 0 / 通过 | 0 / 通过 |

结论：实际分配明显更均匀，本轮TPS与P99没有提高；不把均匀性改善称为吞吐提升，也不从单轮历史差异断言CPU哈希导致下降。

TPS沿用按已提交订单的`created_at`落窗统计口径；该字段早于COMMIT，边界有局限，正式POST数与窗内订单数可不同。

| 桶 | 旧订单/票数（占比） | 新订单/票数（占比） | 新权威 总/锁/售/余 | 等待采样行 旧→新 |
|---|---:|---:|---:|---:|
| 0 | 165/165（3.92%） | 538/538（12.25%） | 7500/543/0/6957 | 0→1 |
| 1 | 681/681（16.19%） | 507/507（11.55%） | 7500/508/0/6992 | 2→6 |
| 2 | 1063/1063（25.27%） | 553/553（12.59%） | 7500/553/0/6947 | 3→1 |
| 3 | 771/771（18.33%） | 584/584（13.30%） | 7500/587/0/6913 | 1→3 |
| 4 | 575/575（13.67%） | 548/548（12.48%） | 7500/552/0/6948 | 1→5 |
| 5 | 431/431（10.24%） | 553/553（12.59%） | 7500/556/0/6944 | 1→2 |
| 6 | 298/298（7.08%） | 539/539（12.28%） | 7500/540/0/6960 | 0→1 |
| 7 | 223/223（5.30%） | 569/569（12.96%） | 7500/572/0/6928 | 0→7 |

分布包含并发预热、正式和排空，旧4207笔、新4391笔，每笔1张；权威余额另含20笔启动检查。零订单桶仍输出0。锁等待仅为约2秒采样行数，不是精确次数或耗时；`mixed-analysis.json/bucketStatistics`还保留逐桶WAITING和GRANTED X采样数。

| 消费者全并发阶段计时均值 | 旧值ms | 新值ms |
|---|---:|---:|
| worker.process | 172.57 | 163.92 |
| MySQL COMMIT往返 | 30.00 | 29.24 |
| Redis settle | 45.43 | 45.70 |
| 桶条件UPDATE往返 | 10.85 | 11.92 |
| 桶X返回至事务完成客户端区间 | 36.37 | 35.11 |

这些区间包含并发预热/排空、相互重叠，不能相加或全归为锁等待。正式窗CPU均值/峰值从40.22%/58.97%变为50.25%/69.22%，最低可用内存15.75GiB，Hikari仍10、pending峰值12；未触发保护，MQ无持续增长且最终排空。

| 可重复模拟输入 | 桶数 | 旧逐桶计数 → 新逐桶计数 | 最大占比 旧→新 | 相对均值最大偏差 旧→新 |
|---|---:|---|---:|---:|
| sparse-80000 | 4 | [0, 40000, 40000, 0] → [20056, 19847, 20128, 19969] | 50.00%→25.16% | 100.00%→0.77% |
| sparse-80000 | 8 | [0, 40000, 40000, 0, 0, 0, 0, 0] → [10046, 9867, 10102, 9980, 10010, 9980, 10026, 9989] | 50.00%→12.63% | 300.00%→1.33% |
| dense-81920 | 4 | [20480, 20480, 20480, 20480] → [20234, 20505, 20582, 20599] | 25.00%→25.15% | 0.00%→1.20% |
| dense-81920 | 8 | [10240, 10240, 10240, 10240, 10240, 10240, 10240, 10240] → [10111, 10159, 10266, 10228, 10123, 10346, 10316, 10371] | 12.50%→12.66% | 0.00%→1.28% |

稀疏样本覆盖10000个毫秒时间片、4个worker、每毫秒序列1/2，共80000条；密集样本为20个毫秒时间片、多个worker、每片4096连号，共81920条。稀疏偏斜被打散；旧密集输入本已均匀，新路由只是统计接近均匀，不保证任意短窗严格相同。

离线微基准：4轮预热后3轮各1000万次，旧取模8.173–8.197ms，新路由9.302–9.401ms，新路由均摊0.930–0.940ns/次，三轮测得热循环分配均0字节。使用变化数组及volatile校验和，避免常量折叠；这是非JMH批量均摊值，不能当作线上单次延迟或整链路TPS。与上表11.92ms的数据库UPDATE往返分开评价。源代码为`StockBucketRouterBenchmark.java`，日志`.local/verification/stock-bucket-route-benchmark.log`。

验证：114项单元/架构检查、23项分桶MySQL用例、11项SINGLE/MQ用例和20项Python检查通过。覆盖4/8桶独立X锁且SKU无X锁、桶7及回滚后跨桶、百买家、防超卖、同ID恢复、重复消费，以及构造旧已提交归属后重放/支付/退款不重算归属。初始化曾被1笔旧库存Outbox待发送拦截，排空后在发单前复核空SKU全部审计通过，保留原失败记录；只执行一轮实际负载。

实际命令：
```powershell
python -B scripts/verification/verify_backend.py --tests StockBucketIT --label uniformroute
python -B scripts/verification/verify_backend.py --tests AsyncPurchaseIT --label uniformsingle
D:/JDK17/bin/java.exe -cp "backend/target/test-classes;backend/target/classes" com.ticketing.StockBucketRouterBenchmark
python -u -B scripts/jmeter/diagnose-purchase.py --runtime .local/jmeter/fourbucket-saturation-20261028/runtime --java D:/JDK17/bin/java.exe --jmeter-jar C:/Users/28298/AppData/Local/Temp/apache-jmeter-5.6.3/bin/ApacheJMeter.jar --concurrency 12 --threads 100 --inflight 100 --capacity 200 --publisher-window 64 --bucket-count 8 --max-posts 7980 --seconds 30
```

实际压测JAR/JMX哈希与运行证明一致；审计总60000、locked4411、sold0、可售55589，与Redis一致，六项桶不变量、重复/孤儿/额度/关联错误及死信均0，pending/recovery/MQ归零，隔离后端正常关闭。未修改Flyway、业务库/SKU、业务卷或生产配置。

变更文件：业务`service/StockBucketRouter.java`、`service/TicketInventory.java`；测试`StockBucketRouterTest.java`、`StockBucketRouterBenchmark.java`、`StockBucketIT.java`及`architecture-modules.properties`；诊断`scripts/jmeter/diagnose-purchase.py`、`analyze-diagnosis.py`；说明`AGENTS.md`、`docs/01-architecture.md`及`DIAGNOSIS.md`。

原始目录：`.local/jmeter/fourbucket-saturation-20261028/c12-p10-w100-d8f1ce/`，保留旧/新派生分析、逐桶余额/锁采样、JTL、订单、资源及审计。未解决的是端到端收益未取得，以及历史对比缺少同批次重复A/B；本轮不追加压测或声称最高性能。
