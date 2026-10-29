# Apache JMeter 性能验证

> 当前V17支持32桶，隔离新环境5000用户、500线程/在途、容量500、72消费者和72连接的命令与结果见[32桶单轮对照](../../docs/jmeter/thirtytwo-buckets-5000users-20261029.md)。当前runtime为`.local/jmeter/thirtytwo-buckets-20261029/runtime`，旧环境凭据已停用；初始化器要求V17。以下V16参数与路径为历史记录，不能直接重跑。

> V16 状态：`diagnose-purchase.py` 闭环入口已改用 `inventory_sync_version` 审计，不再向应用传递发布确认窗口；可同时按真实 HTTP 响应和成功建单时间统计 QPS/TPS。独立 `purchase-http-qps.py` 尚未完成 V16 适配，不要直接执行。历史结果不代表 V16 性能。

仅测购票接口 HTTP QPS/P99 使用 [独立 HTTP 专项入口](HTTP_QPS.md)，按完整响应完成时间统计；不使用完整建单闭环结果推算。

购票统一使用 [安全诊断入口](DIAGNOSIS.md)；活动详情查询使用 [活动 RPS 场景](ACTIVITY_RPS.md)。旧版轮询购票、固定 RPS 发单、独立会话准备及其调度和分析脚本已删除。

## 历史V16购票入口

V16 本轮100线程/100在途、容量200、12消费者/prefetch1、Hikari10、8000旧账号（20启动检查）复现：

```powershell
python -u -B scripts/jmeter/diagnose-purchase.py --runtime .local/jmeter/redis-mysql-optimizations-20261028/runtime --java D:/JDK17/bin/java.exe --jmeter-jar C:/Users/28298/AppData/Local/Temp/apache-jmeter-5.6.3/bin/ApacheJMeter.jar --concurrency 12 --pool 10 --threads 100 --inflight 100 --capacity 200 --max-posts 7980 --seconds 30 --reuse-accounts
```

`--reuse-accounts`只读筛选原账号池内有效且有场次额度的不同用户，每人本轮一笔新幂等键，原领取游标不重置。预登录不计入窗口。至少预热30秒并确认稳定后不断流测30秒；HTTP QPS 使用 `httpCompletedMs` 落在正式窗口内的真实响应数，不从TPS反推。旧 `--publisher-window` 只接受历史命令兼容，不再生效，结果记为null。运行前须单独核实隔离库的V16升级条件，不自动批准迁移。

用户指定的200线程/200在途、Hikari16、容量200对照已完成一轮，实际命令和QPS/TPS见[200线程对照](../../docs/jmeter/v16-200threads-hikari16-20261029.md)。该档使用现有 `--allow-capacity-fill` 明确允许在途达到容量，其他保护保留，不自动提高Redis容量。

用户后续指定72消费者、16桶、500线程/500在途，Hikari16不变；本轮仅隔离容量相应设500。实测及命令见[72消费者对照](../../docs/jmeter/v16-72consumers-16buckets-500threads-20261029.md)，不据此自动修改业务默认值或继续加压。

Hikari72的后续补测按用户指定预登录10000旧账号，并报告停止发单前最后5秒TPS，定义、完整30秒结果及前批账号耗尽限制见[Hikari72末段结果](../../docs/jmeter/v16-hikari72-tail5-20261029.md)。CLI支持 `--pool 72`，不改变业务默认连接池。

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

每轮领取 8000 名独立账号，其中 20 人用于低速检查，7980 人用于连续预热及正式窗口。新建空分桶票档使用同一入口的 `--fresh-bucketed-sku --bucket-count 8` 、`16` 或 `32`，需要隔离后端关闭且旧申请排空；只修改独立测试库。实际测试以票档真实桶数和审计为准。

首次建立新环境使用同一入口的 `--initialize --users <人数>`，仅适用于不存在同名容器及卷的空隔离环境。当前环境可直接复用，不必重新初始化。

## 统计与保护

同一个 JMeter 至少连续预热 30 秒，最近三个 5 秒完成率稳定后，不断流测量后续 30 秒。成功订单 TPS 按最终成功订单的 MySQL `created_at` 落窗计数，该时间早于 COMMIT；完整建单 P95/P99 使用 POST 发起至 MySQL 提交并完成 Redis 结算。HTTP POST RPS/P99 另列，不把 202 受理直接视作建单完成。

约每 2 秒监控 Windows CPU、物理内存、JMeter、后端 JVM、Docker、MySQL 和 RabbitMQ。CPU/内存、严重 GC、错误、持续积压或监控过期触发时，停止新请求，等待已受理订单排空，再核对库存、额度及申请与订单关系。线程上限 500，业务默认容量、发布窗口和消费者不随测试模板变化。

## 结果与清理

最近对照为 [8 桶与 16 桶](../../docs/jmeter/eight-vs-sixteen-bucket-20261028.md)。历史结论保留在 `docs/jmeter/`；旧本地原始数据和已废弃卷已清理，历史文档中的本机产物路径仅是当时记录。

`.local` 仅保留当前环境凭据、账号池、必要运行文件及最近两轮的小型结果摘要和审计。完成测试后及时移除会话文件、JTL、日志、重复 JAR 和临时产物。删除 Docker 卷前核对项目标签和全部容器挂载关系，按名称定向删除；保留业务卷及仍需复用的当前测试卷。
