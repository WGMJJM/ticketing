# Apache JMeter 购票验证

活动详情热点读取的 RPS/P99 场景见 [ACTIVITY_RPS.md](ACTIVITY_RPS.md)。

预登录、正式窗口只发 POST 的购票瓶颈诊断及资源熔断见 [DIAGNOSIS.md](DIAGNOSIS.md)。定位消费能力时优先使用此入口，避免大量状态轮询争抢连接池。

最新隔离验证使用8桶、100线程/100在途、发布窗口64、购票容量200、12消费者；总计预登录8000账号，包含20个低速启动检查账号。先连续预热至少30秒，稳定后测量30秒，仅执行一轮，具体命令见上述诊断说明。12消费者仅用于隔离验证，新建空分桶票档使用`--fresh-bucketed-sku --bucket-count 8`。

本目录只测异步普通购票链路。测试使用独立 Compose 项目 `ticketing-jmeter`、本机端口 13307/16379/15673/15674 和独立命名卷；应用库固定为 `ticketingjmeter`。不要连接根目录 `.env` 指向的 3307 `ticketing` 业务库。

## 分离发单和建单统计

`purchase-session-post-only.jmx` 使用提前真实登录并校验身份的独立 Session/CSRF，每个账号只发一笔单票 POST；正式窗口不登录、不轮询。先用 `prepare-data.py` 创建足量账号和库存，再通过 `prepare-sessions.py --users <账号CSV> --output <私有会话CSV>` 预登录。会话 CSV 包含敏感 Cookie 与 CSRF，只能放在 `.local/jmeter/`，禁止提交或打印。

Windows PowerShell 示例：

```powershell
python -B scripts/jmeter/prepare-sessions.py --users .local/jmeter/measure-users.csv --output .local/jmeter/sessions.csv --workers 8
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/jmeter/run-post-stage.ps1 `
  -SessionsFile .local/jmeter/sessions.csv -OutputDirectory .local/jmeter/purchase-500-example/tier50 `
  -BatchId separatedexample50 -Threads 50 -TargetRps 10 -BackendPid <隔离后端PID>
```

脚本在每档测前核对 Docker 项目、随机数据库 guard、独立会话数、剩余库存及候补；会话供给至少为 `目标 RPS × 30 秒 + 线程数`。统一时间槽按目标速率发单，60 秒爬坡及额外准备时间后才进入 30 秒窗口；窗口结束停止新发单，有界等待 Redis 和 RabbitMQ 排空。资源保护或大量拒绝会停止后续提交，结果不能视为稳定满载能力。不同档位使用互不重叠的独立会话、批次和足量剩余库存。

保护监控约每 2 秒采样主机 CPU、内存、后端及 JMeter 进程，并异步采集 Docker、RabbitMQ 和数据库状态。CPU 高于 85% 持续 15 秒、高于 95% 持续 5 秒，或内存超过 85% / 剩余低于 4 GiB、Docker 内存超过 85%、队列持续积压、严重 GC/OOM 或大量请求错误时，监控写入 `stop.flag`，发单线程读取标记后停止新请求，已受理申请继续排空。采样、监控不可用及触发原因均保留在本轮目录；停止后不得继续提高负载。

`admission.json` 的受理 RPS 只统计有效 202 和申请 ID，P99 是 POST 响应耗时；`order-creation.json` 按 MySQL 订单创建时间统计 TPS，按 Redis 受理时间到 MySQL 订单行创建时间计算成功样本 P99。后者不是客户端轮询观察时间或事务提交完成时间；需核验 Redis 与 MySQL 时钟偏差，披露未完成申请及窗口后排空。逐笔对应、资源快照和原始 JTL 均保存在忽略目录。

## 准备隔离环境

Windows PowerShell 中生成随机本地凭据：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/jmeter/prepare-environment.ps1
```

启动仓库 Compose 中的 MySQL、Redis、RabbitMQ。`--pull never` 禁止拉取镜像：

```powershell
docker compose --project-name ticketing-jmeter --env-file .local/jmeter/compose.env --file deploy/compose.yml up --pull never -d
docker compose --project-name ticketing-jmeter --env-file .local/jmeter/compose.env --file deploy/compose.yml ps
```

在独立 PowerShell 窗口加载这份本地配置并启动唯一 Spring Boot 应用。此命令不调用会导入业务 `.env` 的 `scripts/start-backend.ps1`：

```powershell
Get-Content .local/jmeter/compose.env | ForEach-Object {
    if ($_ -match '^([A-Z][A-Z0-9_]*)=(.*)$') { Set-Item -Path "Env:$($Matches[1])" -Value $Matches[2] }
}
mvn -f backend/pom.xml spring-boot:run
```

等待 `http://127.0.0.1:18080/actuator/health` 返回 `UP`。应用首次启动只会在这个隔离库运行 V1–V15 迁移，并使用临时随机口令初始化一个管理员种子账号。隔离买家复用该随机口令，初始化器从种子账号读取 BCrypt 哈希；CSV 和口令仅在 `.local/jmeter/`，不提交。

## 建立测试数据

需要 Python 及仓库既有 `PyMySQL` 依赖。脚本会核对 Docker 项目标签、MySQL 专用卷、schema 名和 V15，再验证随机 guard。它只接受空的隔离库和初始化管理员，创建 BCrypt 测试用户、邮箱已验证的本地账号、一个合法的已发布活动/未来场次/在售票档，并按账号数设置足量库存。不同账号每人只执行一笔订单；CSV 不循环，EOF 后停止线程。脚本不会创建订单、伪造支付或清理已有数据。

```powershell
python -B scripts/jmeter/prepare-data.py --users 1
Get-Content .local/jmeter/prepared-data.json
```

正式压测可同时准备独立预热用户，例如 `--warmup-users 10 --users 100`。先用 `warmup-users.csv` 和独立 `-JbatchId` 预热并排空，再用 `measure-users.csv` 执行正式窗口。每次迭代使用新用户、新 Session 和新幂等键；准备用户数须覆盖爬坡、预热和正式窗口中预计的请求数。最多创建 10,000 个隔离账号；默认 `purchase.capacity=200`、每用户每场限购 4、每笔限购 1。若受理返回 429、409、503、排队超时或库存错误，该轮不能作为无业务约束下的最大成功 TPS。

## 下载并运行 JMeter

从 [Apache JMeter 官方下载页](https://jmeter.apache.org/download_jmeter.cgi) 下载二进制包，按官方页面提供的 SHA-512 或 PGP 文件校验。解压到 `%TEMP%` 等临时目录，不要把 JMeter 发行包或结果放进仓库。

示例 CLI 命令（替换 `$jmeter`、批次标识和实际 `skuId`）：

```powershell
$jmeter = "$env:TEMP\apache-jmeter-5.6.3\bin\jmeter.bat"
$users = (Resolve-Path .local/jmeter/measure-users.csv).Path
$skuId = (Get-Content .local/jmeter/prepared-data.json -Raw | ConvertFrom-Json).skuId
$batchId = 'smoke-' + (Get-Date -Format 'yyyyMMddHHmmss') + '-' + (Get-Random -Minimum 100000 -Maximum 999999)
$jtl = (Join-Path (Resolve-Path .local/jmeter).Path "$batchId.jtl")
$stopNewOrdersMs = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() + 30000
& $jmeter -n -t scripts/jmeter/purchase.jmx `
  -Jhost=127.0.0.1 -Jport=18080 -Jprotocol=http `
  "-JusersCsv=$users" "-JskuId=$skuId" "-JbatchId=$batchId" `
  -Jthreads=1 -JrampUpSeconds=1 "-JstopNewOrdersMs=$stopNewOrdersMs" `
  -JpollIntervalMs=250 -JmaxPollSeconds=60 `
  -Jsample_variables=testBatch,requestId,orderId,terminalStatus,purchaseOutcome,failureCode,idempotencyKey `
  -Jjmeter.save.saveservice.output_format=csv `
  -Jjmeter.save.saveservice.print_field_names=true `
  -Jjmeter.save.saveservice.response_data=false `
  -Jjmeter.save.saveservice.samplerData=false `
  -Jjmeter.save.saveservice.response_message=true `
  -Jjmeter.save.saveservice.response_code=true `
  -Jjmeter.save.saveservice.assertion_results_failure_message=true `
  -l $jtl
python -B scripts/jmeter/summarize-jtl.py $jtl --poll-interval-ms 250
```

`purchase.jmx` 先获取 CSRF、表单登录、登录后重新获取 CSRF；这三步不进入购票端到端事务。每次交易通过 `Idempotency-Key: jmeter-<batchId>-...` 受理普通购票申请，再按固定间隔轮询到成功/失败/超时。登录、Cookie、CSRF 和响应正文都不写入 JTL。必需的 `stopNewOrdersMs` 是 Unix 绝对毫秒截止时间：截止后不再提交新购票，已提交事务继续观察并写出汇总；可选 `stopFile` 在资源保护时提前停止新提交。CSV 提前耗尽时线程会结束，不会复用账号或幂等键。

## 指标口径与 SQL 核验

- **成功订单 TPS**：以唯一批次幂等键前缀筛选 `CREATE_ORDER` 普通订单，统计 `ticket_order.created_at` 落入 MySQL UTC 正式窗口的订单数，再除以固定窗口秒数。包含创建后仍为 `PENDING` 或之后超时的订单；不把 202 受理、支付、候补订单或窗口外排空订单算入。预热用另一批次，正式窗口结束后的订单另列。
- **订单创建端到端 P99**：从 JMeter 购票 POST 开始到首次轮询观察到 `SUCCEEDED` 且 `orderId` 非空；按幂等键关联 POST、轮询和 Transaction Controller 汇总，只对完整成功样本按最近秩计算 P99，同时报告失败和样本数。结果包含轮询间隔，不是 MySQL 提交耗时。登录与 CSRF 不在此样本内。使用 `--window-start-ms` 和 `--window-end-ms` 按 POST 发起时间筛选窗口，窗口内提交而窗口后完成的事务也计入；`--json` 和 `--cohort-csv` 可保存逐笔汇总。
- JMeter HTTP 吞吐量只代表请求速率。`summarize-jtl.py` 输出成功链路 P99、P95 和失败类别；最终成功订单 TPS 必须由下列 SQL 对账，不可从 JMeter RPS 推算。

正式负载先单独预热，再在稳定负载阶段记录固定窗口的起止 UTC 时间，并校验 MySQL 与客户端时钟偏差。让压测至少持续“爬坡 + 预热 + 正式窗口”。窗口结束后等待购票申请、Redis 恢复索引和 RabbitMQ 队列排空，再执行对账。把 `-JbatchId` 写入 `@batch_prefix` 时加 `jmeter-` 前缀与结尾 `-`，并填写实际 `skuId`、MySQL UTC 起止时间，再只读执行：

不要重新开启线程组的统一定时停止，它会截断正在轮询的事务。应为窗口后的观察预留足够时间，并检查窗口内每次 POST 是否有对应的完整事务样本；如有缺失，成功样本 P99 存在截尾偏差，不能作为完整窗口 P99 报告。

```powershell
docker exec ticketing-jmeter-mysql-1 sh -lc 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot --batch --skip-column-names -e "SELECT UTC_TIMESTAMP(6)"'
```

将以下变量替换后执行 `scripts/jmeter/verify-orders.sql`。脚本另报申请终态、排空订单、幂等关联和库存不变量；受理成功和正式订单不一致、超卖、窗口末尾仍排队或消息积压均须披露。

```sql
SET @batch_prefix = 'jmeter-<batchId>-';
SET @sku_id = <实际 skuId>;
SET @window_start_utc = '<MySQL 返回的 UTC 起点>';
SET @window_end_utc = '<MySQL 返回的 UTC 终点>';
```

测试结束只查看 Redis，不执行删除：

```powershell
docker exec ticketing-jmeter-redis-1 redis-cli HGETALL "ticketing:seckill:v2:ticketingjmeter:sku:<skuId>"
docker exec ticketing-jmeter-redis-1 redis-cli ZCARD "ticketing:seckill:v2:ticketingjmeter:recovery"
docker exec ticketing-jmeter-redis-1 redis-cli GET ticketing:seckill:v2:ticketingjmeter:pending
```

与 MySQL 库存比较 Redis 的 `available`、恢复集合和全局待处理数；集合成员为 `skuId:requestId`。新建票档由后台初始化完成后才开放普通购票；未初始化时只返回受控错误。矛盾时判定本轮无效，不清理后宣称一致。

## 停止与清理

停止专用 Spring Boot 进程后只移除此测试 Compose 项目。`down` 不带 `-v`，不会触碰 `ticketing` 业务项目。若测试数据也不再需要，先核对卷名前缀，再显式删除下列仅属于 `ticketing-jmeter` 的三卷；不要运行全局 prune：

```powershell
docker compose --project-name ticketing-jmeter --env-file .local/jmeter/compose.env --file deploy/compose.yml down
docker volume inspect ticketing-jmeter_mysql_data ticketing-jmeter_redis_data ticketing-jmeter_rabbitmq_data
docker volume rm ticketing-jmeter_mysql_data ticketing-jmeter_redis_data ticketing-jmeter_rabbitmq_data
```

删除本轮 `.local/jmeter` 凭据、CSV、日志和 JTL；它们含临时凭据及测试数据。临时 JMeter 发行包用完后也可删除。当前工作不新增业务接口、迁移或性能框架。
