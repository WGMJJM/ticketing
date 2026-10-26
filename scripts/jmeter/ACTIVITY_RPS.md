# 热门活动详情 RPS 与 P99 测试

本场景只访问隔离后端的 `GET /api/v1/activities/{id}`，用同一个已发布活动模拟热点读取。不会调用登录、场次、购票或其他接口。主测试使用 JMeter CLI 闭环线程；线程数表示并发用户，不代表预设 RPS。

## 隔离环境

PowerShell 中创建随机本地凭据并启动独立 Compose 项目。`ticketing` 业务库、3307/8080 业务服务、业务数据卷都不参与本测试。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/jmeter/prepare-environment.ps1
docker compose --project-name ticketing-jmeter --env-file .local/jmeter/compose.env --file deploy/compose.yml up --pull never -d
docker compose --project-name ticketing-jmeter --env-file .local/jmeter/compose.env --file deploy/compose.yml ps
```

在独立 PowerShell 窗口只加载隔离配置并运行后端（不要加载根目录 `.env`）：

```powershell
Get-Content .local/jmeter/compose.env | ForEach-Object {
    if ($_ -match '^([A-Z][A-Z0-9_]*)=(.*)$') { Set-Item -Path "Env:$($Matches[1])" -Value $Matches[2] }
}
mvn -f backend/pom.xml spring-boot:run
```

等待 `http://127.0.0.1:18080/actuator/health` 为 `UP`，然后在另一窗口建立或复用活动详情夹具：

```powershell
python -B scripts/jmeter/prepare-activity.py
```

夹具脚本验证 Compose 项目标签、MySQL 专用卷、`ticketingjmeter`、V13 与随机 guard。若已有已发布活动则复用；若数据库为空则只创建一条活动记录，因为详情端点只读取 `activity`，不依赖场次或票档。它拒绝清理或覆盖其他数据。

## JMeter 与负载

从 [Apache JMeter 官方下载页](https://jmeter.apache.org/download_jmeter.cgi)下载发行包并校验官方 SHA-512/PGP，解压到 `%TEMP%\apache-jmeter-5.6.3`。运行前确认没有 JMeter GUI。单档命令示例：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/jmeter/run-activity-rps.ps1 `
  -Threads 10 -RampUpSeconds 30 -WarmupSeconds 60 -MeasureSeconds 120
```

建议依次以 10、25、50、100、200 线程各运行一档；每档后先检查错误、10 秒吞吐桶、P99、客户端/容器资源，再决定是否继续。候选最高稳定档重复两次（合计三轮）。不要在资源明显耗尽或错误上升时继续升档。JMeter、后端和依赖服务同机，因此结果代表本机隔离栈的端到端表现，不代表独立压测机下的服务端极限。

PowerShell 入口每档都生成唯一批次，预检只确认隔离服务健康和目标活动详情正确；分析成功后原始 CSV JTL 使用 gzip 无损压缩，连同日志、摘要及不含凭据的运行元数据写入 `.local/jmeter/rps-p99/<batch>/`。结果目录被 `.local/` 忽略，不会提交。runner 记录的 Redis keyspace hit/miss 是隔离 Redis 全库计数，不是活动目录缓存专属命中率。

## 指标与稳定性判定

分析脚本以唯一 `RPS timing marker` 为起点，按完成时间 `timeStamp + elapsed` 选择爬坡后预热完毕的固定正式窗口；不计入标记、爬坡、预热和结束样本，也不重复计数控制器样本。

- 总 RPS = 正式窗口内全部活动详情 GET 数 / 固定窗口秒数。
- 成功 RPS = HTTP 200、`code=OK`、活动 ID 匹配且状态为 `PUBLISHED` 的样本数 / 固定窗口秒数。
- 主表 P99 = 成功样本的最近秩 P99（`ceil(0.99 × N)`）；摘要也提供全部样本 P99、错误率和失败分类。
- 分析器把至少 1000 个正式样本、错误率不高于 1%、没有连续两个 10 秒桶低于非空桶成功 RPS 中位数 50%、末尾三桶 P99 中位数不超过开头三桶两倍，作为“最高已验证稳定档位”的技术筛查规则。它不是业务 SLA；报告同时保留客观实测值，不宣称系统极限。

缓存默认显式开启。当前实现为 Caffeine L1 15 秒、Redis L2 120 秒；每档固定一个活动，先预检并运行 60 秒预热。活动详情只读 `activity`，没有实时读取场次/票档，故热点命中不受库存变化查询干扰。每轮报告记录 Redis 命中计数差和 Docker 容器状态，用于辅助解释缓存和资源状况。

需要快速对照每次读取数据库的表现时，在启动隔离后端前，将 `.local/jmeter/compose.env` 中的 `CATALOG_CACHE_ENABLED` 改为 `false`，然后加载配置并启动后端。运行入口增加 `-DisableCache`，会核对配置并用 `activity-rps-nocache-` 单独标记批次。例如 `-Threads 25 -RampUpSeconds 5 -WarmupSeconds 10 -MeasureSeconds 30 -DisableCache` 只做一次短测，不用于寻找最大稳定吞吐量。这里只关闭活动目录 Caffeine/Redis 缓存，MySQL 缓冲池仍正常工作。

停止后端与专用 Compose 项目时，只对 `ticketing-jmeter` 使用 `down`。不要运行全局 prune；业务服务、业务库和业务数据卷保持不动。保留本次忽略目录中的原始 JTL 供复核，完成后删除其中凭据文件和临时 JMeter 下载。
