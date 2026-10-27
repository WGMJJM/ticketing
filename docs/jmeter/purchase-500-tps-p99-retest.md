# 500 线程异步购票复测及瓶颈观察

## 环境和口径

2026-10-27 在本机隔离环境执行，业务代码基于 `a66a69574ff26f9cdea0475b097da3fa18573e2c`，本次 JMeter 工具修改在该提交之后、测试前应用。Windows 主机 i5-13400F、16 逻辑处理器、约 31.8 GiB 内存，Docker 可用 15.53 GiB；Java 21.0.10、JMeter 5.6.3，单后端实例、JMeter 和中间件同机。后端 G1 最大堆约 8 GiB，JMeter 堆 1 GiB。项目为独立 `ticketing-jmeter`、数据库 `127.0.0.1:13307/ticketingjmeter`、专用 Redis、RabbitMQ 及各自专用卷；项目标签、卷和随机数据库 guard 均已核对。没有加载业务 `.env` 或连接业务端口。

仅隔离后端设置 `ticketing.purchase.capacity=1000`，实际 JVM 参数已核验；消费者并发 2、prefetch 1、队列期限 120 秒、发布确认窗口 16、确认超时 3 秒，保持默认。准备 100 个独立预检账号、9,900 个正式账号，单笔一张，库存 10,000。最后一次 5 线程预检 20 次提交全部完成；之前两次预检发现并修复事务收尾截断。预检使用独立账号和批次，并在正式测试前排空。

唯一正式批次为 `measure500retest20261027`：60 秒爬坡至 500 线程，再经过约 13 秒稳定阶段。预先固定正式窗口 **2026-10-27 04:49:56.767–04:50:26.767 UTC**（北京时间 12:49:56.767–12:50:26.767），30 秒。JMeter 启动时间为 04:48:43.372 UTC，窗口内每秒采样均以 500 个活动线程为主；线程包含登录、CSRF、提交和轮询，不等于同时有 500 个建单请求。CSV 未耗尽。

窗口时间使用客户端 Unix 毫秒，执行前与 MySQL UTC 三次校时的偏差绝对值小于 0.24 ms，查询往返小于 0.8 ms；启动时另记录 Stopwatch 单调计数和频率，资源采样记录单调时间。截止后停止启动新 POST，已开始的请求继续每 250 ms 轮询，客户端最长轮询 60 秒，业务队列期限没有改变。窗口内 253 笔 POST 的成功观察全部跨过窗口结束；截止后新 POST 为 0。

## 结果和数据完整性

- **成功订单 TPS 8.433**：MySQL 以批次及 `CREATE_ORDER` 筛选、关联申请和订单明细，按照订单 `created_at` 统计，窗口内创建 253 笔，除以固定 30 秒。窗口前创建 443 笔，窗口后排空阶段创建 497 笔，均不计入窗口 TPS。窗口内订单可能来自窗口前的提交，因此与同窗 POST 不必一一对应。
- **成功订单端到端 P99 43,257 ms**，P50 32,927 ms、P95 42,458 ms；样本为正式窗口内 POST 发起、获得 202、首次观察到 `SUCCEEDED` 且有有效订单 ID 的 253 笔。按 POST 与首次成功轮询完成时间之差取最近秩；包含 250 ms 轮询间隔，不包含登录和 CSRF。该值只描述成功请求，不是纯数据库耗时或最大生产性能。
- 窗口内 POST 253 次、202 受理 253 次、拒绝 0；成功观察 253、业务 `FAILED` 0、`EXPIRED` 0、客户端轮询超时 0、未完成/截尾 0。请求 ID、事务汇总和订单 ID 均完整匹配，覆盖率 **253/253**。窗口内 POST 约 8.43 次/秒；MySQL `accepted_at` 在窗口内为 251 笔，边界差异来自客户端发起与服务端受理时刻不同。
- 全正式批次 POST 1,193 次，其中 1,180 次返回 202；爬坡期间 13 次返回 `503 PURCHASE_PUBLISH_UNCONFIRMED`，均不在正式窗口，之后数据库确认这 13 笔也最终建单成功。它们没有被当作 HTTP 成功受理。批次最终数据库申请 `SUCCEEDED` 1,193、`FAILED` 0、`EXPIRED` 0。未观测到网络错误；完整窗口样本无拒绝或超时。

## 一致性

排空后，正式批次成功申请与订单的用户、票档、数量及幂等键关联异常 0，缺申请订单 0、订单项异常 0、重复订单幂等键 0、多个申请关联同一订单 0。含 44 笔预检订单，MySQL 库存总量 10,000、锁定 1,237、已售 0、可售 8,763；Redis 可售 8,763，SKU 和全局待处理均为 0，恢复索引 0。购票队列 ready 0、unacked 0，死信队列 0。未出现失败或过期申请，故本轮未验证失败释放路径。

## 瓶颈证据与限制

**已观察事实：**正式窗口 RabbitMQ 采样 ready 约 550→672 条，unacked 持续 2，消费者数 2；窗口内两次累计 ACK 采样为 479→769，约 10.6 条/秒，采样和管理 API 更新有误差。消息排队延迟明显，窗口后停止新提交时积压逐步排空。正式窗口 POST 响应 P50/P95/P99 为 95/261/366 ms，远低于成功端到端 P99；该 HTTP 时间包含入口、Redis 和发布确认，无法单独分离发布确认耗时。

窗口中两次线程采样分别发现约 155、183 条 Hikari `ConcurrentBag.borrow` 栈；窗口结束约 1 秒后的独立线程快照为 135 条，其中 134 条属于 HTTP 线程、1 条属于购票消费者，18 条包含购票申请详情查询调用。能确认当时存在连接池借用等待，但不能据此计算各阶段的等待时长或证明其独立因果。MySQL 采样连接数约 11（包含监控连接），默认 Hikari 池未调大。MySQL 全局行锁等待计数在窗口的两次采样间增加 75 次、累计等待时间增加约 1,908 ms；这些是全库指标，不能全归因于 SKU。低频快照曾看到 1 个数据锁等待，但未捕获到可归属 SKU 的现场。整个监测阶段的 Performance Schema 汇总中，SKU `SELECT ... FOR UPDATE` 约 1,197 次、平均 5.229 ms、历史最大 502.028 ms；库存条件更新约 1,193 次、平均 0.215 ms。这些累计值包含爬坡及排空，不是窗口内逐笔事务耗时。

窗口中主机总 CPU 采样 33.2%–50.4%，可用物理内存约 15.5 GiB；JMeter 工作集约 920–937 MiB、约 539 个线程，后端工作集约 581–593 MiB、约 289–299 个线程。按窗口两次进程累计 CPU 近似计算，JMeter 约占 0.5 个逻辑核心、后端约 2.5 个逻辑核心；未观察到发压端 CPU 饱和。两次容器采样中 MySQL 约 43%–49% 单核、Redis 约 4%、RabbitMQ 约 2%。JMeter 在启动阶段记录一次 `System.gc()` Full GC 约 17 ms，后端未记录 Full GC；未发现 OOM 或客户端网络错误。低频采样不能排除短时峰值或交换，也没有持续测量连接池等待时长。

**有证据的推断：**在本次同机负载下，持续队列积压、两个持续未确认消费和大量连接池等待共同说明，消费及其共享数据库连接资源供给不足以跟上当前提交与轮询负载；高频状态轮询可能参与连接竞争。正式窗口有 39,742 次状态轮询，Performance Schema 在整个监测阶段记录超过 11 万次申请查询。SKU 锁竞争存在可能，尚不足以证明它是主要瓶颈。JMeter CPU 数据不支持将发压端 CPU 饱和作为主要解释，但无法排除同机资源竞争及监测开销。

上轮为 23.3 TPS，本轮为 8.433 TPS；运行时资源、监测和队列动态不同，不能把差异归因于某一个配置。本轮没有证明单纯将消费者从 2 调到 4 就能解决问题，故没有做配置对照，也未修改业务代码或连接池。

## 运行命令与原始证据

以下为实际执行的关键命令和参数；`$dir` 是 `.local/jmeter/purchase-500-retest-20261027`，口令仅存在该忽略目录的运行配置和账号 CSV 中。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/jmeter/prepare-environment.ps1
# 将独立 compose.env 的 PURCHASE_CAPACITY 改为 1000。
docker compose --project-name ticketing-jmeter --env-file .local/jmeter/compose.env --file deploy/compose.yml up --pull never -d
# 独立进程加载上述 compose.env，并在隔离后端启用 GC 文件记录：
java -Xlog:gc:file=<本轮目录>/backend-gc.log -jar backend/target/ticketing-backend-0.1.0-SNAPSHOT.jar --ticketing.purchase.capacity=1000
python -B scripts/jmeter/prepare-data.py --warmup-users 100 --users 9900
# 5 线程预检使用独立预检 CSV、批次和截止时间。
jmeter -n -t scripts/jmeter/purchase.jmx -Jhost=127.0.0.1 -Jport=18080 -JusersCsv=<正式CSV绝对路径> -JskuId=1 -JbatchId=measure500retest20261027 -Jthreads=500 -JrampUpSeconds=60 -JstopNewOrdersMs=1793076626767 -JstopFile=<本轮目录>/stop.flag -JpollIntervalMs=250 -JmaxPollSeconds=60 -Jsample_variables=testBatch,requestId,orderId,terminalStatus,purchaseOutcome,failureCode,idempotencyKey -Jjmeter.save.saveservice.output_format=csv -Jjmeter.save.saveservice.print_field_names=true -Jjmeter.save.saveservice.response_data=false -Jjmeter.save.saveservice.samplerData=false -Jjmeter.save.saveservice.response_message=true -Jjmeter.save.saveservice.response_code=true -Jjmeter.save.saveservice.assertion_results_failure_message=true -l <本轮目录>/measure.jtl -j <本轮目录>/jmeter.log
python -B scripts/jmeter/summarize-jtl.py <本轮目录>/measure.jtl --window-start-ms 1793076596767 --window-end-ms 1793076626767 --json <本轮目录>/cohort-summary.json --cohort-csv <本轮目录>/cohort.csv
python -B scripts/jmeter/test_summarize_jtl.py
```

`measure.jtl`、逐笔 `cohort.csv`、窗口、资源采样、JVM GC、MySQL 摘要及只读对账结果、RabbitMQ 排空和线程快照保存在上述 Git 忽略目录，未保存登录响应、Cookie 或 CSRF。本轮独立容器已停止，专用数据卷和 `runtime/compose.env`、账号 CSV 为独立复核保留；可用该配置重启独立 Compose，通过 `scripts/jmeter/verify-orders.sql` 和上述 UTC 边界查询，不得连接业务库。压测计划、统计脚本和三项合成边界测试已检查，未运行新的完整业务测试套件。

简历候选：在本机隔离环境使用 500 个 JMeter 线程测试异步购票，测得成功订单 TPS 8.433、成功订单端到端 P99 43.257 秒。使用独立测试数据核对购票申请、订单、库存和消息的最终一致性，并通过完整样本追踪分析排队及连接池等待。
