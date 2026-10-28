# Apache JMeter 性能验证

仅测购票接口 HTTP QPS/P99 使用 [独立 HTTP 专项入口](HTTP_QPS.md)，按完整响应完成时间统计；不使用完整建单闭环结果推算。

购票统一使用 [安全诊断入口](DIAGNOSIS.md)；活动详情查询使用 [活动 RPS 场景](ACTIVITY_RPS.md)。旧版轮询购票、固定 RPS 发单、独立会话准备及其调度和分析脚本已删除。

## 当前购票入口

- `diagnose-purchase.py`：隔离检查、预登录、持续预热、JMeter 发单、资源保护、排空和库存审计。
- `purchase-post-only.jmx`：正式窗口只发购票 POST，由完整建单完成事件驱动闭环补发。
- `diagnosis_stats.py`、`analyze-diagnosis.py`：窗口统计与诊断分析。
- `prepare-environment.ps1`、`prepare-data.py`：仅用于创建独立环境与测试数据。
- `test_diagnosis_*.py`、`test_redis_probe.py`：当前诊断工具的必要检查。

业务验证继续使用 `scripts/verification/` 及原后端、前端测试。业务 Redis Lua 和 Flyway 文件不属于清理范围。

## 复用当前环境

保留的运行目录为 `.local/jmeter/redis-mysql-optimizations-20261028/runtime`，对应 Compose 项目 `ticketing-jmeter`、数据库 `ticketingjmeter`、端口 13307/16379/15673/15674。凭据与账号 CSV 只能留在忽略目录，不得提交或打印。不要加载业务 `.env` 进行压测。

当前账号池共 48000 人，领取游标已用尽。后续执行前先确定账号预算，再用 `--prepare-account-pool <人数>`补齐；不要重置游标或重复领取。该选项只补缺额，不重建既有账号。

以下是最近对照的参数模板，替换本机 Java/JMeter 路径并准备足量账号后执行：

```powershell
python -u -B scripts/jmeter/diagnose-purchase.py `
  --runtime .local/jmeter/redis-mysql-optimizations-20261028/runtime `
  --java <java.exe路径> --jmeter-jar <ApacheJMeter.jar路径> `
  --concurrency 12 --pool 16 --threads 200 --inflight 200 `
  --capacity 1000 --publisher-window 256 --max-posts 7980 --seconds 30
```

每轮领取 8000 名独立账号，其中 20 人用于低速检查，7980 人用于连续预热及正式窗口。新建空分桶票档使用同一入口的 `--fresh-bucketed-sku --bucket-count 8` 或 `16`，需要隔离后端关闭且旧申请排空；只修改独立测试库。实际测试以票档真实桶数和审计为准。

首次建立新环境使用同一入口的 `--initialize --users <人数>`，仅适用于不存在同名容器及卷的空隔离环境。当前环境可直接复用，不必重新初始化。

## 统计与保护

同一个 JMeter 至少连续预热 30 秒，最近三个 5 秒完成率稳定后，不断流测量后续 30 秒。成功订单 TPS 按最终成功订单的 MySQL `created_at` 落窗计数，该时间早于 COMMIT；完整建单 P95/P99 使用 POST 发起至 MySQL 提交并完成 Redis 结算。HTTP POST RPS/P99 另列，不把 202 受理直接视作建单完成。

约每 2 秒监控 Windows CPU、物理内存、JMeter、后端 JVM、Docker、MySQL 和 RabbitMQ。CPU/内存、严重 GC、错误、持续积压或监控过期触发时，停止新请求，等待已受理订单排空，再核对库存、额度及申请与订单关系。线程上限 500，业务默认容量、发布窗口和消费者不随测试模板变化。

## 结果与清理

最近对照为 [8 桶与 16 桶](../../docs/jmeter/eight-vs-sixteen-bucket-20261028.md)。历史结论保留在 `docs/jmeter/`；旧本地原始数据和已废弃卷已清理，历史文档中的本机产物路径仅是当时记录。

`.local` 仅保留当前环境凭据、账号池、必要运行文件及最近两轮的小型结果摘要和审计。完成测试后及时移除会话文件、JTL、日志、重复 JAR 和临时产物。删除 Docker 卷前核对项目标签和全部容器挂载关系，按名称定向删除；保留业务卷及仍需复用的当前测试卷。
