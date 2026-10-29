# V16 源码收尾与下一阶段验收

2026-10-29 从 GitHub main 快进拉取到 `92b48e8` 后实施。本轮没有启动应用或容器、创建账号、连接业务数据库、迁移或清理数据库，也没有运行任何单元、集成、故障、浏览器或 JMeter 测试。

## 已修复

1. 正常 V16 功能审计的 `TABLES` 删除 `outbox_event`，加入实际存在的 `inventory_sync_version`，结果计数同步调整；没有捕获不存在表的 SQL 错误来绕过审计。
2. 隔离验证的成功清理白名单加入 `ticketing_inventory_migration_<16位hex>_it`；其他随机后缀也按各测试实际生成的12/32位收紧。固定实例和随机 guard、启动前数据库集合、整轮成功后清理、Flyway 失败现场保留的规则不变。本轮未实际删除任何数据库。
3. `MigrationSafetyConfig` 在 `flyway.migrate()` 前阻断所有非已知终态购票申请（包括 QUEUED、历史 PROCESSING）及候补 PROCESSING；保留明确授权、未排空/未知旧事件检查，补齐旧事件序号溢出阻断。WAITING/ALLOCATED 不受此条件阻断。V1～V16 迁移文件全部未改，无新增结构迁移；不能绕过应用预检直接升级历史库。
4. 正常验收配置的候补扫描从60000统一为5000毫秒，保留环境变量覆盖；删除旧 Outbox 批量配置和两个废弃测试开关，删除示例中的候补续跑队列容量。普通购票消费者、队列和必要开关保留。
5. 库存扫描由两次 HGETALL/重复解析合并为一次。仍有预扣、版本不同或尚未就绪时省去该次余额 SQL；无预扣且版本一致时保留实际余额检查。删除重复的 `inventoryBalanceDiffers()` 方法；SQL 读取异常向上抛出，不当成账本损坏处理。真正发布继续使用原 SKU X 锁、pause/pending 及 Lua 版本检查。

## 源码核查结论

取消、订单超时、场次取消释放、退款、票档编辑/上下架、活动发布/下架、场次窗口/状态变化，以及候补入队、退出、分配和失败终结，均已通过 `InventoryVersionService` 在原事务内更新同步版本。本轮未发现这些路径还需新增版本更新的确定性遗漏。

普通建单的预扣/提交/结算不会重复扣库存；支付 locked→sold 不改变可售量，候补 ALLOCATED→COMPLETED 也不改变队首门禁；WAITING→PROCESSING 仍是同一有效队首，不为这些无镜像变化的状态追加无意义版本写入。原 SINGLE/BUCKETED、4/8/16桶路由与分配、不可变 allocation、Redis Lua、支付/退款/电子票及 FIFO 规则未改。

WaitlistDispatcher、WaitlistCompensation、WaitlistBackgroundJob、WaitlistService 中未发现仍在执行的 Confirm/Outbox 专用失败分支或独立候补投递方法。前次提交已删除相关 Java 类，本轮没有需要再次删除的 Java 文件；仅删除上述冗余方法、配置和文档中的失效当前说明，没有新增服务、状态机或中间表。

以上为源码核查结论，不能替代运行时一致性或恢复验收。现有 HTTP 部署检查不能证明 MQ/Redis 已排空，迁移流程已明确列出必须另行完成的只读检查。

## 修改文件

- 配置/验证：`.env.example`、`scripts/verification/environment.py`、`verify_backend.py`、`application-verification.yml`、`audit.sql`、`backend/src/test/resources/application.properties`。
- 主代码：`MigrationSafetyConfig.java`、`RedisStockReservationService.java`、`StockSnapshotService.java`。
- 测试代码：扩展 `InventorySyncMigrationIT.java`；新增 `service/RedisInventoryReconciliationTest.java`。只编译，没有执行。
- 现行文档：`docs/01-architecture.md`、`02-database-design.md`、`03-api-contract.md`、`05-local-development.md`、`40-functional-verification.md`、`41-inventory-sync-migration.md`、本文件；更新 `AGENTS.md`。
- 压测说明：`scripts/jmeter/README.md`、`HTTP_QPS.md`、`DIAGNOSIS.md` 加入 V15 历史入口及 V16 待适配说明，没有修改压测脚本或生成结果。

## 实际执行的检查

- `mvn -f backend/pom.xml test-compile -DskipTests`：BUILD SUCCESS，编译174个主代码文件、46个测试代码文件；没有执行测试。存在已有弃用 API/unchecked 编译警告。
- Python AST：`scripts/verification` 下4个 Python 文件语法解析成功，没有导入或执行入口。
- YAML：主应用、功能验收、运维、Compose 四个 YAML 文件解析成功，没有启动服务。
- Git 静态差异与引用检查：未改迁移、Lua、分桶实现或前端；正常功能入口无旧表/废弃配置引用。旧表只保留在升级预检、历史迁移测试及明确待适配的旧压测工具中。

## 留待下一阶段

1. 执行新增迁移阻断/放行用例：授权与未授权、QUEUED/历史 PROCESSING、候补 PROCESSING、未知/未完成事件、保持订单及 allocation、V16 重启不读旧表；确认成功清理及失败现场保留。
2. 执行库存版本同事务回滚、取消/超时/退款/管理变化同步，预扣未归零不覆盖、旧版本拒绝、重复扫描、账本丢失/损坏停卖、SQL/Redis 故障后恢复。
3. 执行候补 FIFO、PROCESSING requestId/attemptNo 绑定、前一申请未结算时队首阻塞及原订单生命周期回归。
4. 在独立环境统一执行原单元、MySQL/Redis/RabbitMQ 集成、故障注入、业务审计和浏览器回归；业务迁移须另行取得具体授权。
5. 旧购票 JMeter 脚本仍读取旧 Outbox/传递发布窗口，尚不能用于 V16；下一阶段适配后再测接口 QPS/P99 与订单 TPS，不引用历史结果作为新版本性能。本轮不报告性能提升或故障恢复成功。

目前没有新发现并留而不修的确定性业务错误；旧压测工具适配和所有运行时验收仍未完成，不能宣称 V16 已验收通过。
