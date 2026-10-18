# 第八阶段压测复现

本工具只操作本机专用 `ticketing-perf` Compose 项目。MySQL 库固定为 `ticketing_perf`，端口13307；RabbitMQ端口15673、管理端口25673；Redis端口16379；后端18080。不会导入根目录 `.env`，也不连接开发库 `ticketing`。数据库控制标记位于独立 `performance_control` 库，避免影响Flyway初次迁移。原V1至V4保持不变。

必须在没有真实业务数据的独立环境执行。`init` 自动生成随机数据库、MQ、管理员密码及签名密钥，均保存在被忽略的 `.local/performance/`。每次写入或清理前检查随机数据库标记；已有未标记数据拒绝接管。故障命令的Compose项目名、文件和端口固定，不支持传入任意生产目标。不要复制压测账号、Cookie或有效电子票到共享报告。

Docker上下文必须使用本机npipe/unix套接字，远程TCP/SSH上下文直接拒绝。启动专用后端时清除子进程中可能重定向Spring数据源、端口或注入JVM参数的继承变量，再注入本工具配置；不修改系统或开发环境变量。允许记录的调参覆盖值仅限已列明的布尔值/有限整数。

## 准备及基线

需要Java21、Maven、Docker Compose、Python3.12、k6。Python依赖见 `scripts/performance/requirements.txt`。本次实际k6版本与校验和记录在环境报告中。可从[Grafana官方发行页](https://github.com/grafana/k6/releases)下载与本机匹配的二进制，并核对同一发行版的SHA256校验和。不要将二进制加入Git。

在仓库根目录执行：

```powershell
python -m pip install -r scripts/performance/requirements.txt
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/performance/install-k6.ps1
mvn -f backend/pom.xml clean verify
New-Item -ItemType Directory -Force .local/performance | Out-Null
Copy-Item backend/target/ticketing-backend-0.1.0-SNAPSHOT.jar .local/performance/baseline.jar
python scripts/performance/perf.py init
python scripts/performance/perf.py start --jar .local/performance/baseline.jar --pool-size 10 --outbox-batch-size 20
python scripts/performance/suite.py --label baseline --levels 10,25,50,100 --warmup 10 --duration 20
```

Compose使用现有镜像和 `--pull never`，缺镜像时显式失败，不自动拉取。单场景示例：

```powershell
python scripts/performance/perf.py prepare --scenario hot-scarce --users 50
python scripts/performance/perf.py run --scenario hot-scarce --vus 50 --warmup 10 --duration 20 --label local-check
python scripts/performance/perf.py audit
```

可选场景为 `query`、`hot-ample`、`hot-scarce`、`mixed`、`waitlist`、`checkin`。`prepare` 自动停止压测进程，按外键顺序清理**已验证专用库中的业务表**，保留迁移和环境标记，重启后重新注册独立用户、创建等价活动/场次/票档。不要在压测时运行其他构建或另一组压力。

工具优先使用 `--k6` 指定的文件，其次系统PATH和私有工具目录。自定义安装位置可在 `run` 或 `suite` 后传 `--k6 C:/tools/k6.exe`。`start`显式参数会记录并用于每轮重启；当前代码也可使用原始参数复现参数对照。完整历史版本复现可用 `git archive c0cd5d5` 导出源码到私有目录构建，不能覆盖当前工作区。

查询和热点单目标场景的`run`也可同时传入 `--activity-id`、`--session-id`、`--sku-id`，先在专用库核对父子归属再使用；默认采用自动准备的ID。混合场景的64场次、候补的持单/排队关系及核销的已付票券由prepare整体生成，不接受忽略关联关系的单ID替换。工具不会允许切换到其他库或远程URL。

## 流量模型

每档先预热10秒，再稳定20秒，采用k6闭环 `constant-vus`。数据包含1000条已发布活动。所有写请求均经过真实用户名密码登录、Session Cookie和CSRF；登录后重新获取令牌。每个VU使用独立用户，幂等键由运行ID、VU和迭代号共同决定，定期原键重放并核对原订单ID。

- 查询依次调用活动列表、详情、场次、票档，公开查询不会为身份额外查账号。
- 库存充足热点为同SKU100万张；稀缺热点为同SKU5张；创建后真实取消以形成持续竞争。稀缺场景不是累计卖出量测试，最终不能把预期库存冲突计为系统错误。
- 混合交易使用64个场次轮转，在保持默认每场4张限购的情况下交替支付和取消，避免全部流量很快被限购耗尽。每个成功支付真实写流水和电子票。
- 候补使用N个持单用户占满N张，再由N个不同用户顺序申请，稳定阶段并发取消持单，经Outbox、RabbitMQ分配，候补用户真实支付。HTTP统计包含轮询，另报告有限队列的分配数量与投递延迟，不能将轮询RPS当作分配TPS。
- 核销先真实支付签发一张票，k6在内存获取完整凭证，N个独立管理员竞争同一票。成功必须只有一次，其余是预期409；不是不同票券的核销容量测试。

HTTP指标只给固定路由名称，不使用动态URL、用户、订单或票号作为标签；409还需匹配明确业务错误码。5xx、超时、未知冲突和认证失败不能视为成功。报告不保存请求/响应正文，禁止使用 `--http-debug`。`handleSummary`显式剔除可能含电子票的k6 `setup_data`。

## 监测及审计

压测配置将已有Actuator端点放到 `/api/v1/admin/monitoring`，复用既有ADMIN授权和数据库账号状态复查。普通用户和匿名不能读JVM/连接池指标；正常开发配置仍只暴露health/info。每2秒采集JVM CPU、堆、GC、线程、Hikari活跃/等待/获取连接耗时、MySQL行锁等待和慢SQL、MQ队列深度/消费确认、Outbox数量/重试及投递延迟。只保存慢查询数量和归一化SQL摘要，不导出含参数的原始慢日志。

`audit.sql` 在一致性快照中核对库存边界、SKU逐笔订单汇总、金额/支付快照、场次限购、候补关联及FIFO、已支付票券数量/序号/归属、核销元数据和重复效果。这里只适用于零历史差额的独立夹具，不能直接拿它断言真实业务全库库存等于某次压测订单数。`audit_probes.py` 自动注入4种表CHECK无法完整表达的业务差异，要求审计失败并还原，再确认通过。

```powershell
python -m unittest discover -s scripts/performance -p test_safety.py
python scripts/performance/faults.py
python scripts/performance/audit_probes.py
python scripts/performance/diagnose.py
```

故障脚本真实停止/恢复专用MQ、断开消费者连接、重放实际Outbox消息、停止/恢复专用MySQL、停止应用跨越真实支付截止时间再重启，并验证禁用用户库存释放。等待截止不修改订单时间。后台任务重试、租约、数据库补偿仍是原实现；不引入生产故障控制API。

## 优化比较及输出

先保留基线，再构建优化版本、停止压测进程并更换jar，以相同并发、数据规模、数据库及连接池配置重新运行相关场景。结果目录标签不能重复，避免覆盖基线。

```powershell
python scripts/performance/perf.py stop
mvn -f backend/pom.xml clean verify
python scripts/performance/perf.py start --jar backend/target/ticketing-backend-0.1.0-SNAPSHOT.jar --pool-size 10 --outbox-batch-size 100
python scripts/performance/suite.py --label optimized --levels 10,25,50,100 --warmup 10 --duration 20
python scripts/performance/report.py
```

每个场景保存 `k6-summary.json`、`monitoring.json`、`database.json`、`environment.json`。稳定RPS/TPS用自定义计数除以稳定秒数，不使用包含预热/登录的k6 Counter.rate。延迟采用稳定请求样本P50/P95/P99，不平均各轮分位数。MySQL累计差值覆盖整轮预热、稳定及消息排空，与稳定HTTP窗口不同；说明中必须保留这一区别。

TPS明确指稳定窗口内成功创建订单的数量除以秒数；“关联支付成功数”用于核对本轮创建/分配订单的后续结果，可能包含窗口后的收尾，不能将它除以秒数宣称标准支付TPS。核销测试报告同票成功次数和重复拒绝RPS，不将重复拒绝吞吐当作不同票券的入场容量。`diagnose.py`关闭背景任务后记录单次HTTP操作SQL数量，并用独立连接持有用户行锁，验证同用户跨场次串行及不同用户可以前进的现有协议。

20秒稳定窗口用于本机瓶颈定位，不构成长时间浸泡测试、极限容量或生产SLA。闭环模型在响应变慢时会降低到达速率，不能据此宣称支持同等开放流量。完整原始结果与实际环境/分析见同目录验收报告。

构建前端后可用 `python scripts/performance/preview.py` 启动127.0.0.1:15173的本地预览，静态资源来自 `frontend/dist`，API固定代理至专用18080后端。它不改动原Vue路由或开发环境代理。登录账号只从私有夹具读取，用于浏览器真实闭环，不把密码写进报告。

结束用 `python scripts/performance/perf.py stop` 停止专用应用；Docker可使用相同专用Compose文件及私有env执行 `stop` 保留数据。无需删除开发环境或执行全局 `prune`。根目录README仍仅本地保留。
