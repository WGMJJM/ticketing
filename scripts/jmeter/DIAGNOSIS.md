# 购票 TPS 安全诊断

2026-10-28最新用户配置为100线程、JMeter在途100、隔离实例购票容量200、发布确认窗口64、8消费者/prefetch=1，仍使用单SKU四桶。脚本默认同步这组参数，发布窗口源码默认64，业务消费者默认仍2；8消费者只用于本轮隔离验证。最多预登录4000个独立账号，其中20个用于低速启动检查，3980个用于并发预热和正式窗口；预算不足则标记本轮无效并报告，不自动增加账号。首批请求分2秒放行，此后由各自完整建单完成驱动补发，不设置正式RPS。CPU/内存/监控/75%绝对容量保护全程生效，预热初始有界队列填充与稳定阶段持续增长分别判断。

```powershell
python -u -B scripts/jmeter/diagnose-purchase.py --runtime $runtime --java $java --jmeter-jar $jmeterJar --concurrency 8 --threads 100 --inflight 100 --capacity 200 --publisher-window 64 --max-posts 3980 --seconds 30
```

本组4000账号实测及中断原因见[100线程与8消费者验证](../../docs/jmeter/four-bucket-100threads-8consumers-4000users.md)。首轮受旧SKU到期维护影响，补测用剩余账号在正式第22秒耗尽，未取得完整30秒的有效容量结果。

四桶验收使用 `--fresh-bucketed-sku`，不可使用只建立SINGLE的`--fresh-sku`。本轮保留Redis命令1s/连接500ms。此前4消费者/32发布窗口以及500线程/容量1000/256发布窗口均为历史批次，报告保留实际参数与结果，不按当前默认值改写；本轮不运行下文历史消费者A/B示例。

当前保留原有20笔低速启动检查及其排空，随后按相同并发持续预热30秒，最近三个5秒段的成功建单完成率极差不超过均值20%时进入正式计时；未稳定则每5秒复查，预热总计最多60秒，仍不稳定则停止并标记未进入正式测量。并发预热与正式窗口使用同一JMeter、同一SKU和连续在途窗口，不排空、不重启、不二次爬升。`--seconds 30`仅表示正式测量30秒，`--max-posts`及账号预算覆盖并发预热和正式全过程。原始JTL保留全阶段，summary分别保存`warmupStats`与`allPhases`；正式P99只纳入正式窗口发起的成功202，订单TPS按创建时间统计，包含预热期发起而正式窗内建成的订单。

预登录按工作线程复用HTTP连接，减少Windows短连接端口开销；每个买家先清空Cookie、重新建立认证会话和CSRF，核对响应用户名，并验证全部Cookie互不重复。复用TCP不等于复用买家身份。

新批次可用 `--initialize --users 50000 --previous-runtime <旧隔离runtime>`，核验旧guard/卷/排空后仅轮换隔离容器，旧卷保留。初始化及预登录均持续采资源，账号在正式窗口前准备，单轮 `--max-posts` 上限50000、总账号上限60000。先 `--host-only`，再初始化、新建四桶、闭环自检，最后逐档手动运行：

```powershell
python -u -B scripts/jmeter/diagnose-purchase.py --runtime $runtime --java $java --jmeter-jar $jmeterJar --fresh-bucketed-sku --users 50000
python -u -B scripts/jmeter/diagnose-purchase.py --runtime $runtime --java $java --jmeter-jar $jmeterJar --concurrency 4 --threads 2 --inflight 2 --max-posts 20 --seconds 10 --selftest
python -u -B scripts/jmeter/diagnose-purchase.py --runtime $runtime --java $java --jmeter-jar $jmeterJar --concurrency 4 --threads 100 --inflight 100 --max-posts 8000 --seconds 30 --publisher-window 32
```

用户于2026-10-28执行中将本轮改为单组30秒，不追加更高档位。未来若另行探索128在途，必须先检查上一轮资源、正确性与预算；150在途会触及当前容量200的75%熔断边界，运行器拒绝启动。闭环每笔仅凭自己的已结算终态返还额度；明确未预扣的过滤器/Lua拒绝单独返还；其他503/网络失败按原键有界查询，不确定则UNKNOWN并停发。正常请求不轮询业务查询接口。

`valid/wholeRunValid` 保留旧严格条件；`correctnessValid` 要求四桶/订单/额度/Redis/Outbox/排空一致且无未知请求；`performanceCohortValid`只表示202成功延迟样本可核验，不能替代整轮容量有效性。恢复成功的503计入固定窗口真实订单TPS，延迟另列。`bucket-audit.json`使用桶余额和不可变allocation；配置行SHARE及桶X持有区间分开记录。主机磁盘PDH与容器BlockIO也写入资源证据。

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
python -B scripts/jmeter/diagnose-purchase.py --runtime $runtime --java $java --jmeter-jar $jmeterJar --inflight 0 --rps 1 --seconds 10 --selftest
```

初始化只接受没有同名容器或卷的新环境，不自动清理已有数据。先核验项目标签、端口、独立卷，在空 schema 迁移前将随机 guard 绑定同项目 Redis；V15 迁移及管理员初始化完成后，既有建数器建立数据库 guard 和最多 10000 个独立买家。失败时保留现场，不能自动删除 guard、repair 或重建数据卷。

多轮耗时可能跨过默认 15 分钟支付窗口。旧订单到期释放会产生库存 Outbox，库存快照更新会短暂停止该票档的新预扣，此时 `PURCHASE_ADMISSION_UNAVAILABLE` 不等于消费者吞吐不足。应保留异常结果；若有账号余额，可用同一命令附加 `--fresh-sku` 创建正常 SINGLE 票档、保留原票档与全部订单，再在新票档上从头完成同负载的所有消费者对照。它只更新本地 `prepared-data.json` 指向并备份旧元数据，不重建库、不复用账号、不停用到期扫描。不要跨不同 SKU/背景负载的组计算优化百分比；旧票档释放仍有数据库背景开销，要通过 task 计时和锁记录说明。

自检预热与正式账号分离。正式阶段在至少 5 条 JTL 写入后创建停止文件；应输出 `selftestPassed=true`、`postsAfterStop=0`、`drained.complete=true`、`checks.valid=true`。自检的 `valid=false` 是预期行为，因为主动中止了完整窗口；它不参与 TPS 基准。

## 逐档运行

### 不限制 RPS 的闭环饱和诊断

需要寻找当前机器与当前业务链路的吞吐平台时，使用 `--inflight`，不要把 `--rps` 当作 TPS 上限：

```powershell
python -u -B scripts/jmeter/diagnose-purchase.py --runtime $runtime --java $java --jmeter-jar $jmeterJar --concurrency 2 --inflight 64 --max-posts 2400 --seconds 30
# 根据资源、对账、队列和上一档结果，逐次测试 4/8 消费者及 96 在途窗口。
```

此模式不设置到达速率。仅在 MySQL 已提交且 Redis `settle` 返回成功后，诊断记录无凭据的申请ID、订单ID、终态和完成时间，最近记录上限512。观察器每50ms读取 loopback 内存指标，不查询购票状态或借数据库连接；JMeter 每个线程必须看到自己的终态才进入下一次请求，HTTP 202 不代表完成。最初一批在2秒内启动，之后完全随建单完成速度补入，不设置RPS。窗口上限限制未完成申请数；将 `--threads` 与 `--inflight` 都设为100，即100并发用户的完整建单闭环。

启动阶段按实际发单时间错开请求，已经等待的线程继续按间隔发送，不会在启动窗口结束时集中追赶；之后不限制发单速率。`--publisher-window`支持16、32、64或256，默认64，并记录在运行证明和结果中。当前四桶验收允许4或8消费者、发布窗口32/64/256，覆盖历史复现与本轮配置。比较优化前后的SKU锁时必须使用相同槽位数，不能把调整窗口的影响归为SQL优化。

JTL 保存每笔发放时的完成计数、`orderCompletedMs/orderObservedMs/orderStatus`，结果验证实际在途未超限、完整成功样本与全部202一致。`postToSettledMs` 为HTTP提交到SQL提交且Redis结算成功的完整链路耗时；`postToClientObservedMs` 为测试线程通过诊断观察器看到自己终态的耗时，包含本机观测传播，不能冒充前台状态查询接口的延迟。观察器异常或超过2秒未更新即停新POST；2秒资源监控与原资源/容量熔断保留。观察器请求耗时和实际最大采样间隔单列，未做关闭仪表的开销A/B。

直接测试100线程的示例：

```powershell
python -u -B scripts/jmeter/diagnose-purchase.py --runtime $runtime --java $java --jmeter-jar $jmeterJar --concurrency 2 --threads 100 --inflight 100 --max-posts 4300 --seconds 60
```

固定的是测量时间和在途请求上限，不是 RPS/TPS。比较 64→96 在途请求而 TPS 不再增长、队列持续有待处理消息、消费者确实忙碌，可以判断该配置出现吞吐平台；不能仅因某个窗口得到一个数就声称机器极限。闭环各组的实际输入率会随完成率变化，不能冒充相同 offered RPS 的开放到达因果对照。CPU 没有满载也可能已受消费者槽位或 SKU 串行锁约束。

`--max-posts`只限制本轮独立账号预算，不限制发压速率。若账号提前耗尽，`accountBudgetExhausted=true`，该轮不具备容量结论。每轮提前登录预算账号；已发单及失败账号不再复用。本轮总预算4000人，环境干扰中断后，只从JTL索引及MySQL核对确实未发单的剩余账号补测，保留原失败批次，不扩大总用户数。

旧批次余额不足时，可在应用已正常关闭、无在途购票和其他数据库事务后轮换独立批次：

```powershell
python -B scripts/jmeter/diagnose-purchase.py --runtime .local/jmeter/capacity-next/runtime --java $java --jmeter-jar $jmeterJar --initialize --previous-runtime $runtime
```

轮换须核验旧库随机 guard、项目全部容器、每个卷标签、loopback 端口和消息排空；只停止并移除该项目三个已核验的中间件容器。旧卷、凭据、元数据和测量结果全部保留，新批次使用新命名卷，未执行删库或删卷。`retained-previous-batch.json` 保存旧卷名称，新的 `volumes.override.json` 保存新卷映射；失败保留现场，不自动重试清理。新批次仍是唯一 `ticketing-jmeter` 项目、`ticketingjmeter` schema 和原隔离端口。

### 可复现的开放到达对照

```powershell
python -u -B scripts/jmeter/diagnose-purchase.py --runtime $runtime --java $java --jmeter-jar $jmeterJar --concurrency 2 --pool 10 --inflight 0 --rps 10 --seconds 30
# 仅在上一档安全且对账通过后提高负载。固定负载对照只改变 concurrency。
python -u -B scripts/jmeter/diagnose-purchase.py --runtime $runtime --java $java --jmeter-jar $jmeterJar --concurrency 4 --pool 10 --inflight 0 --rps 40 --seconds 30
python -u -B scripts/jmeter/diagnose-purchase.py --runtime $runtime --java $java --jmeter-jar $jmeterJar --concurrency 8 --pool 10 --inflight 0 --rps 40 --seconds 30
python -B scripts/jmeter/analyze-diagnosis.py .local/jmeter/diagnosis-new
python -B -m unittest discover -s scripts/jmeter -p 'test_*.py'
```

每档先预登录，再以20个独立账号低速启动检查并排空；之后JMeter默认100线程、100在途（`--inflight 0`显式选择历史开放到达模式），只向固定loopback地址发POST，按绝对到达时间或完成闭环及绝对截止时间调度，不执行登录、CSRF获取或状态轮询。每笔使用独立Session/CSRF/账号/幂等键，失败账号也不再复用。`next-account.json`是本地独占执行的账号游标，不支持多运行器并发。

初始预热只能消除部分冷启动影响，不能保证 JVM 已达长期稳态。正式窗口最少 30 秒；30 秒结果只属于短窗口诊断。不要把所有档位串成一个无人监督的自动增压循环。`noEscalation=true` 后不得继续增压。只有发现池等待证据后，才考虑同负载的 `--pool 16` 对照。

## 熔断和排空

资源采样目标间隔 2 秒，间隔超过 5 秒失败关闭。CPU >85% 连续 15 秒禁止增档；>95% 连续 5 秒停止新 POST。可用内存 <4 GiB 或占用 >85% 直接停止，比需求更保守。其他停止条件包括后端工作集 >1 GiB、JMeter >768 MiB、后端堆 >85%、GC 暂停占比 >10%、容器内存风险、队列/Redis 在途达到容量的 75%、约 10 秒实时Redis在途持续增长超过30条、近期大量拒绝/网络失败、监控异常。Rabbit管理统计延迟刷新不能单独作为持续增长证据。近期拒绝除最后20笔中的5笔外，还检查近4秒内至少5笔且不低于5%的拒绝。

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
