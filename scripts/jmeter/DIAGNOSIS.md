# 购票 TPS 安全诊断

此入口专用于 Windows 本机 `ticketing-jmeter`、`127.0.0.1:13307/ticketingjmeter`，不加载业务 `.env`。普通购票仍保持 Redis 原子预扣、RabbitMQ 发布确认、消费者 MySQL 事务、提交后 Redis 结算。诊断不改变业务算法、持久化、锁序或默认并发参数。

## 前置条件

- Java 21、Maven、Python（已有 `requests`、`pymysql`）、Docker Desktop、JMeter 5.6.3。
- 复用本机缓存的 MySQL 8.4、Redis 7、RabbitMQ 4.1 镜像；初始化使用 `--pull never`。
- 至少 4 GiB 可用物理内存。脚本会读取 Windows CPU/内存、受控 JVM、容器、MQ、MySQL 和 Hikari；监测失败即停止新 POST。
- 应用 `backend/target/ticketing-backend-0.1.0-SNAPSHOT.jar` 已完成 `mvn -f backend/pom.xml verify`。
- 不同时运行其他购票压测或启动第二个应用。端口 18080/18081 必须空闲。

## 初始化与熔断自检

以下路径示例须替换为本机工具路径。运行目录必须位于 `.local/jmeter/`；不要复制业务环境文件。

```powershell
$java = 'D:/path/to/java21/bin/java.exe'
$jmeterJar = 'E:/path/to/apache-jmeter-5.6.3/bin/ApacheJMeter.jar'
$runtime = '.local/jmeter/diagnosis-new/runtime'
python -B scripts/jmeter/diagnose-purchase.py --runtime $runtime --java $java --jmeter-jar $jmeterJar --host-only
python -B scripts/jmeter/diagnose-purchase.py --runtime $runtime --java $java --jmeter-jar $jmeterJar --initialize
python -B scripts/jmeter/diagnose-purchase.py --runtime $runtime --java $java --jmeter-jar $jmeterJar --rps 1 --seconds 10 --selftest
```

初始化只接受没有同名容器或卷的新环境，不自动清理已有数据。先核验项目标签、端口、独立卷，在空 schema 迁移前将随机 guard 绑定同项目 Redis；V15 迁移及管理员初始化完成后，既有建数器建立数据库 guard 和最多 10000 个独立买家。失败时保留现场，不能自动删除 guard、repair 或重建数据卷。

多轮耗时可能跨过默认 15 分钟支付窗口。旧订单到期释放会产生库存 Outbox，库存快照更新会短暂停止该票档的新预扣，此时 `PURCHASE_ADMISSION_UNAVAILABLE` 不等于消费者吞吐不足。应保留异常结果；若有账号余额，可用同一命令附加 `--fresh-sku` 创建正常 SINGLE 票档、保留原票档与全部订单，再在新票档上从头完成同负载的所有消费者对照。它只更新本地 `prepared-data.json` 指向并备份旧元数据，不重建库、不复用账号、不停用到期扫描。不要跨不同 SKU/背景负载的组计算优化百分比；旧票档释放仍有数据库背景开销，要通过 task 计时和锁记录说明。

自检预热与正式账号分离。正式阶段在至少 5 条 JTL 写入后创建停止文件；应输出 `selftestPassed=true`、`postsAfterStop=0`、`drained.complete=true`、`checks.valid=true`。自检的 `valid=false` 是预期行为，因为主动中止了完整窗口；它不参与 TPS 基准。

## 逐档运行

```powershell
python -u -B scripts/jmeter/diagnose-purchase.py --runtime $runtime --java $java --jmeter-jar $jmeterJar --concurrency 2 --pool 10 --rps 10 --seconds 30
# 仅在上一档安全且对账通过后提高负载。固定负载对照只改变 concurrency。
python -u -B scripts/jmeter/diagnose-purchase.py --runtime $runtime --java $java --jmeter-jar $jmeterJar --concurrency 4 --pool 10 --rps 40 --seconds 30
python -u -B scripts/jmeter/diagnose-purchase.py --runtime $runtime --java $java --jmeter-jar $jmeterJar --concurrency 8 --pool 10 --rps 40 --seconds 30
python -B scripts/jmeter/analyze-diagnosis.py .local/jmeter/diagnosis-new
python -B -m unittest discover -s scripts/jmeter -p 'test_*.py'
```

每档先预登录，再以 20 个独立账号预热并排空；之后 JMeter 24 个线程只向固定 loopback 地址发 POST，按绝对到达时间与绝对截止时间调度，不执行登录、CSRF 获取或状态轮询。每笔使用独立 Session/CSRF/账号/幂等键，失败账号也不再复用。`next-account.json` 是本地独占执行的账号游标，不支持多运行器并发。

初始预热只能消除部分冷启动影响，不能保证 JVM 已达长期稳态。正式窗口最少 30 秒；30 秒结果只属于短窗口诊断。不要把所有档位串成一个无人监督的自动增压循环。`noEscalation=true` 后不得继续增压。只有发现池等待证据后，才考虑同负载的 `--pool 16` 对照。

## 熔断和排空

采样目标间隔 2 秒，间隔超过 5 秒失败关闭。CPU >85% 连续 15 秒禁止增档；>95% 连续 5 秒停止新 POST。可用内存 <4 GiB 或占用 >85% 直接停止，比需求更保守。其他停止条件包括后端工作集 >1 GiB、JMeter >768 MiB、后端堆 >85%、GC 暂停占比 >10%、容器内存风险、队列/Redis 在途达到容量的 75%、约 10 秒持续队列增长超过 30 条、近期大量拒绝/网络失败、监控异常。

Rabbit 管理统计存在刷新延迟，所以同时读取实时 Redis 在途量。停止文件在 `HttpClient.send()` 前检查，已发请求继续返回；之后有界等待 MQ、Redis pending、recovery 归零。未排空则保留本轮 JVM，不终止中间件、不破坏事务。每次停止都应检查 `stop.flag` 和原始资源样本。修复诊断工具后再运行也使用新账号，不抹掉失败轮次。

排空后用独立随机环境令牌向本机管理端口 `/actuator/shutdown` 请求 Spring 正常关闭，并等待监听器、定时任务、HTTP 与数据源退出。该接口仅在诊断模式显式开启，要求精确 loopback/端口/路径/POST 和恒定时间令牌核对；业务 CSRF 不受影响。启动时先验证无令牌请求被 403 拒绝；令牌不写入命令行、日志或结果文件。正常关闭失败会保留 JVM 与 `shutdown.json`，不退回强杀。

## 结果口径与证据

- `summary.json`：预先固定的 `[start,end)`，MySQL `ticket_order.created_at` 窗口内独立订单 / 窗口秒数。窗口外排空单列；202 不能替代建单。查询时用三次往返估计 MySQL/Windows 时钟差。
- `post.jtl`：`postStartMs` 与 `postElapsedMs` 才是实际 POST 发起时间和耗时。JMeter 自带 `timeStamp/elapsed` 包括到达节拍等待，不能用作 HTTP 延迟或固定窗口筛选。
- `orders.json`：只保留批次申请、订单标识及时间，不含口令、Cookie 或票券。受理 requestId、幂等键逐一对应，审计关联、重复、孤儿、金额、额度、库存守恒和死信。
- `resources.jsonl`：CPU、物理内存、工作集、Hikari、MQ、MySQL 状态/锁等待、容器资源。管理端点不借数据库连接；MySQL 只读采样约每两秒一轮，线程快照仅 +10/+22 秒各一次。
- `metrics.json`：正式发单前至排空后的计数/总耗时差，包括窗口外排空。阶段平均值有明确区间；Micrometer P50/P95/P99 是滚动估计，可能含预热，不能宣称严格窗口样本分位数。
- `digests.json`：Performance Schema digest 前后差，MySQL 语句计时不是客户端 Mapper 往返耗时。锁全局计数也不能归因到单一 SKU。
- `runtime-proof.json`：实际 PID、Java 命令、消费者数量/prefetch、池上限/超时、Docker 标签/卷。端点只在显式诊断配置下开放 loopback 18081；错误 schema/端口在数据源初始化前拒绝。
- `analysis.json`：只读汇总；MQ ready 斜率为固定窗口资源样本的线性拟合，若已触发停发，不能当连续稳态斜率。

`ticketing.trade.lock.duration{resource=stock}` 包含取锁 SQL/映射/网络及可能等待，不是持锁时间。额外 `stock.held.client` 从 SKU 锁定查询返回到事务 `afterCompletion`，覆盖提交，属于客户端近似区间。MyBatis-Plus 3.5.17 的 `selectOne` 使用 `selectWithCursor/queryCursor`，本工具同时覆盖 cursor 和常规 query；不遍历或提前关闭游标。有效样本须确认 SKU 存在且持锁计数等于成功事务数；空结果游标的区间不能解释为持有现有行锁。

没有客户端首次观察终态的时间，所以客户端端到端 P99 固定标为未采集。数据库受理至创建的 P99 包括服务端等待与建单，但创建时间早于事务提交，不能冒充提交完成或客户端终态时延。

所有原始数据位于 Git 忽略目录。`compose.env`、CSV、`sessions.json` 含有效凭据，不得提交或输出；其他日志也仅本地保留。诊断结束保留隔离卷与原始证据，后续清理须明确目标，不执行全局 prune。
