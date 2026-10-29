# 购票接口 HTTP QPS 与 P99

> 以下为 V15 历史执行说明。该脚本及复用的诊断审计尚未适配 V16（旧 Outbox 和 Confirm），目前不能直接运行，下一阶段统一处理。

入口为 `purchase-http-qps.py`，计划为 `purchase-http-qps.jmx`。线程收到完整 POST 响应后即可使用下一独立账号继续请求，无需等待订单终态。原消费者、Redis 预扣、发布确认、认证和 CSRF 均真实运行。

```powershell
python -u -B scripts/jmeter/purchase-http-qps.py `
  --runtime .local/jmeter/redis-mysql-optimizations-20261028/runtime `
  --java D:/JDK17/bin/java.exe `
  --jmeter-jar C:/Users/28298/AppData/Local/Temp/apache-jmeter-5.6.3/bin/ApacheJMeter.jar `
  --tiers 100 --reuse-accounts `
  --warmup-seconds 30 --seconds 30 --accounts-per-tier 8000
```

命令复用现有固定路径，不加载业务 `.env`。要求隔离环境在线、后端 18080/18081 端口空闲、元数据指向 8 桶票档。用户后续明确允许旧账号，因此`--reuse-accounts`只读筛选ACTIVE、已验证邮箱、USER角色且当前场次还有购票额度的已有用户；同一轮每个POST仍使用不同用户和新幂等键，不重置原领取游标，也不创建账号。预登录在计时外完成。

不传`--reuse-accounts`时，仍使用此前严格的未领取账号方式，领取游标只增不减；不足时通过既有安全入口补齐。默认档位为1/2/5/10/25/50/100/200，`--tiers 100`按用户要求直接测100线程。未稳定或保护触发时没有有效正式窗口；中断观测只能按短暂窗口标记，不能用作稳定QPS结论。

真实配置固定为 12 消费者、prefetch1、Hikari16、发布确认256、购票容量1000。独立 Redis pending 观测每约100ms刷新，仅用于全局容量门禁；到500即停止新请求，早于原75%保护线。现有约2秒主机/JVM/JMeter/Docker/MQ/GC及错误保护全部复用，持续积压保护从预热开始生效。门禁触发或其他异常会令该档无效并停止升档，不把受门禁限制的突发结果当作接口上限。

先运行5笔真实请求自检，检查自动停发、排空和库存；随后依次运行档位。每档至少预热30秒，并检查最近三个5秒HTTP完成率及pending稳定性；最迟额外等待30秒，仍不稳定则停发。稳定后不断流进入固定正式窗口。同档不更换线程、业务逻辑或排空队列。达到保护线、账号预算不足或无效窗口时停止后续档位。

JTL只采集购票POST，不保存Cookie、CSRF、请求或响应正文。字段`httpCompletedMs`为读取完整HTTP响应体后的时刻，正式窗口按`[startMs,endMs)`归档；总HTTP QPS包含202、429、503等全部有效HTTP响应，网络失败单列。P99取202的`postElapsedMs`最近秩百分位，该值由HTTP发送前后单调时钟计算，排除账号等待、容量门禁和订单结算耗时。JTL自带`elapsed`含脚本执行时间，不用于本专项P99。

运行目录为现有runtime同级`http-qps-*`，每档保存原始`post.jtl`、`run.json`、资源日志、`final-audit.json`和`summary.json`。会话文件在发压进程退出后删除。审计只核对申请终态、订单关系、幂等、额度、逐桶库存及Redis一致性；本专项不生成订单吞吐指标。主汇总`highestVerified`只选取有效且资源安全的固定窗口，不宣称未测试档位的能力。

统计与保护检查：

```powershell
python -B -m unittest discover -s scripts/jmeter -p 'test_http_qps_stats.py'
```
