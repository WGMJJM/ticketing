# 多票档管理与库存隔离

## 批量新建草稿

运营工作台打开活动的「场次」→「票档管理」→「批量新增票档」。填写票档名称、票价、总库存、单笔限购和销售窗口，按需添加或移除行，最后点击「创建全部草稿」。草稿仍逐个执行「启用销售」。

`POST /api/v1/admin/sessions/{sessionId}/tickets/batch` 使用管理员 Session 和 CSRF。请求体如下，价格单位为人民币分，销售时间带时区。

```json
{
  "tickets": [
    {
      "name": "VIP 内场",
      "priceCents": "128000",
      "totalStock": 2000,
      "maxQuantityPerOrder": 2,
      "saleStartsAt": "2027-01-03T10:00:00+08:00",
      "saleEndsAt": "2027-02-05T18:00:00+08:00"
    },
    {
      "name": "看台一等票",
      "priceCents": "98000",
      "totalStock": 5000,
      "maxQuantityPerOrder": 4,
      "saleStartsAt": "2027-01-03T10:00:00+08:00",
      "saleEndsAt": "2027-02-05T18:00:00+08:00"
    }
  ]
}
```

成功返回 HTTP 200，`data` 是与输入顺序一致的票档数组。每个草稿有独立 SKU ID，采用 `SINGLE` 库存策略。批量和单个创建共用管理锁序、销售窗口校验和数据库名称唯一键。管理员持场次写锁后核对 200 档上限，每个 SKU 写一条创建审计，提交后统一使缓存失效。任一行失败则整个事务回滚，包含已写入的草稿和审计。无效字段或销售窗口返回 400，重名、超过场次上限或场次状态冲突返回 409。

## 用户选择与交易隔离

活动详情先选场次，再从当前场次的公开票档中选择一个。页面使用该 SKU 的单价、余票、单笔限额和销售状态。购票及候补面板共享本场额度查询。换档重置数量并恢复所选 SKU 上已保留的原申请。已受理申请可从「购票进度」继续查看，当前标签页保留的原幂等键也可用于找回结果。

现有库存协议以 SKU 为单位。Redis Lua 按 `skuId` 预扣和记账，消费者持活动及场次共享锁，再锁该买家的场次额度行和目标 SKU。不同买家购买不同 SKU 可以各自提交。同一买家在同一场次的额度仍按 `(user_id, session_id)` 串行更新。订单明细保存原 SKU，支付、取消、超时、退款、出票及候补沿用其归属。库存同步按 SKU 版本更新，购票容量与中间件资源仍由整个实例共享。

## 隔离验证

真实后端集成验证复用 [功能验证入口](40-functional-verification.md)。`TicketManagementIT` 验证四档批量创建、名称唯一、整批回滚及两个管理员并发创建时的数量上限。`AsyncPurchaseIT` 用 MySQL 真正的行锁阻塞一个 SKU，经 HTTP 和 RabbitMQ 验证另一个 SKU 先提交，并覆盖跨档限购、售罄、下架、失败释放及原 SKU 履约。浏览器验证覆盖切档数量及金额、单次额度查询、批量管理以及切档后回到原申请。

## JMeter 对照复现

使用 [JMeter 诊断入口](../scripts/jmeter/DIAGNOSIS.md)，先构建后端 JAR。对照启用新建的独立 Compose 项目和全新账号，每个用户只发一笔，每笔购买一张。各组使用三个预发检查账号，随后保持相同负载连续预热 30 秒，再测正式窗口。三组总库存均为 30000。

| 档位 | 库存配置 | 发行比例 |
| --- | --- | --- |
| `single` | 单档 30000 | 100% |
| `uniform` | 三档各 10000 | 1:1:1 |
| `hot` | 三档各 10000 | 80%:15%:5% |

```powershell
$runtime = '.local/jmeter/multi-sku-comparison/runtime'
$java = (Get-Command java).Source
$jar = '<JMeter 安装目录>/bin/ApacheJMeter.jar'
python -B scripts/jmeter/diagnose-purchase.py --runtime $runtime --java $java --jmeter-jar $jar --initialize --fresh-project --users 3610
python -B scripts/jmeter/diagnose-purchase.py --runtime $runtime --java $java --jmeter-jar $jar --prepare-sku-comparison
foreach ($profile in 'single', 'uniform', 'hot') {
    python -B scripts/jmeter/diagnose-purchase.py --runtime $runtime --java $java --jmeter-jar $jar --sku-profile $profile --inflight 0 --rps 20 --threads 24 --seconds 30 --concurrency 12 --pool 16 --capacity 200
    if ($LASTEXITCODE) { break }
}
```

`summary.json` 记录 HTTP 受理、成功建单、请求到结算的分位数、档位分布、行锁等待采样、连接池和队列峰值。发压结束后等待 RabbitMQ、Redis 在途和恢复索引排空，再逐 SKU 核对库存、订单、申请和额度。用固定负载的三组结果对照同一环境下的行为。最大稳定吞吐需要在隔离环境下逐阶提高同一组负载并确认瓶颈。

### 2026-11-22 本机对照

Windows 11、i5-13400F、32 GB 内存，JMeter 5.6.3 与后端使用 Java 21。同一 JAR 和同一 JMeter 计划，每组 24 个发送线程、固定 20 请求/秒、12 个消费者、prefetch 1、Hikari 16、Redis 在途容量 200。三组每组 1203 个独立买家，3 笔启动检查、预热 600 笔、正式 600 笔，每笔一张。三组正式档位分布分别为 600、200/200/200、480/90/30。[Apache JMeter 下载](https://jmeter.apache.org/download_jmeter.cgi)

| 组别 | 正式 HTTP 受理 RPS | 正式成功建单结算 TPS | HTTP P99 | POST 到结算 P95 / P99 | SKU 行锁等待采样命中 | RabbitMQ ready / unacked 峰值 | Hikari 活跃 / 等待峰值 | 排空确认 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 单 SKU | 20.00 | 20.00 | 98 ms | 192 / 274 ms | 10 | 0 / 2 | 5 / 0 | 0.19 s |
| 三 SKU 均匀 | 20.00 | 19.97 | 101 ms | 172 / 236 ms | 0 / 0 / 0 | 0 / 4 | 3 / 0 | 0.23 s |
| 三 SKU 热点 | 20.00 | 20.00 | 86 ms | 157 / 201 ms | 1 / 0 / 0 | 0 / 3 | 3 / 0 | 2.39 s |

TPS 按正式窗口内成功结算的 600、599、600 笔计算，耗时按正式窗口发出的成功请求统计。三组正式发出的 600 笔各自全部成功，窗口边界允许订单跨窗结算。整体 3609 笔申请全部成功，拒绝、失败、超时、死信、到期未处理订单和 SKU 路由错配均为 0。三组中间件均排空，逐档库存、订单、申请和额度审计通过。全程主机 CPU 峰值 41.85%，最少剩余物理内存 17.6 GiB，未触发资源保护。

在这一固定到达率下，均匀组的建单结算 P99 从 274 ms 降到 236 ms，行锁等待采样从 10 次降到 0 次。热点组仅在 80% 流量的 SKU 捕获一次等待。每组各执行一轮，采样可漏掉短暂等待，三组吞吐均贴近投放速率。更高负载下的吞吐上限及热点饱和拐点仍待逐档验证。

本轮通过 113 项常规后端测试、83 项真实中间件集成测试、10 项 Edge E2E 及浏览器后 SQL 审计、前端严格类型检查和生产构建、24 项 JMeter 工具测试。原始结果目录依次为 `.local/jmeter/multisku-20261122/c12-p16-r20-e047a3`、`c12-p16-r20-0a83cd` 和 `c12-p16-r20-ded954`。
