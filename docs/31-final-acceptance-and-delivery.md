# 第十九阶段最终验收与交付

执行基线为 `9830a2658bd9789a64a91ffa6702a74a1b487c3c`。开始时 fetch 后本地/远端 main 相同，工作区干净。最终代码以本报告所属提交及分批交付历史为准，保留下文的功能、迁移与部署验收记录，旧性能结果及测量辅助内容已清理。

## 范围、基线与审查方式

源码仍为一个 Java21 / Spring Boot3 模块，167个生产类按业务包登记，唯一应用为 `TicketingApplication`。默认SINGLE、额外Lua准入关闭、消费者2/prefetch1、Outbox窗口16、Hikari最大10、持久受理容量200。所有普通订单通过真实HTTP202/请求/Outbox/Rabbit/消费者链路。没有新增迁移、生产中间件或第二个后端服务。

当时人工重点审查受理/恢复、交易锁序、退款/核销、库存分桶、消息流水线、四类扫描器和前端身份/恢复/凭证生命周期。`docs/01`～`05`和`25`～`30`用于业务约束对照；旧测量环境及源码清单已在后续清理中删除，保留下来的功能验收记录仅代表当时结果。

本轮先执行普通 `mvn clean verify` 和前端 `npm ci`/严格构建，再执行 [完整基线](architecture/results/stage19-baseline-tests.json)：87项普通、255项真实集成，共342项，失败/错误/跳过均0。这是本轮运行结果，未继承阶段18通过数。新增队列溢出/失败隔离/关闭及V10缺CREATE VIEW测试后，[定向回归](architecture/results/stage19-target-tests.json)为90项普通和24项真实集成，全部通过。

| 实际执行命令 | 最终结果与证据 |
| --- | --- |
| `mvn -f backend/pom.xml clean verify` | 90项通过；`.local/stage19/final-ordinary-backend.log` |
| `python -u -B scripts/verification/verify_backend.py --label s19final2` | 90普通+256真实集成全部通过；`.local/stage19/final-integration-r2-backend.log` |
| 前端目录`npm ci`、`npm run build` | 安装审计0漏洞，严格构建通过，39个dist摘要一致 |
| `TICKETING_E2E_LABEL=stage19-r9`，前端`npm run e2e` | 真实Edge 10组通过，最终浏览器报告与审计独立归档 |

普通与集成命令会重复执行普通测试，346是最终完整profile中的用例数，不把重复运行叠加为新增业务覆盖。保留首轮失败和定向复测。V1～V10没有新增或改写，最终源码快照同时索引部署、CI、前端E2E及关键根配置。

## 功能与一致性映射

以下路径以 `/api/v1` 为前缀。最终执行计数及结果汇总见同目录 `stage19-final-validation.json`，浏览器与HTTP/JUnit分别记录。

| 用户功能及入口 | 服务与数据库/事务 | 异步与恢复 | 测试定位/分类 |
| --- | --- | --- | --- |
| 首页/活动详情；GET activities、sessions、tickets | ActivityService/SessionService/TicketService；Activity/Session/TicketSkuMapper；公开缓存及实时库存 | CatalogCache提交后换代 | ActivityManagementIT、TicketManagementIT、CatalogCacheIT；已测试 |
| 注册、登录、登出、个人中心；auth/* | AccountService/UserAccountMapper、BCrypt、SecurityConfig、CurrentAccountFilter | Redis限流、实时账号角色校验 | AuthenticationIT、CsrfSessionIT、TrustedProxyIT、RateLimitIT；已测试 |
| 选票→202抢票请求→本人恢复；POST orders、GET purchase-requests/lookup及详情 | PurchaseEntryService/PurchaseAdmission/PurchaseRequestMapper；账号UPDATE、唯一键、预算事务 | PurchaseCommands/Outbox、PurchaseOrderConsumer、PurchaseRecoveryJob | AsyncPurchaseIT及浏览器；已测试 |
| 容量/重复同键/异参409/过载503 | purchase_capacity与非终态请求严格相等；失败回滚，不捕获唯一键后继续 | 恢复截止、令牌与容量条件释放 | AsyncPurchaseIT；覆盖幂等冲突、受理预算边界及恢复 |
| 用户场次额度、跨票档限购 | UserSessionQuotaService/Mapper；父资源之后额度UPDATE | 取消/过期/退款同事务释放；支付不加额 | UserSessionQuotaIT、QuotaMigrationIT；已测试 |
| SINGLE库存及正式订单 | TradeResourceLocker→OrderReservation→TicketInventory→OrderMapper；READ_COMMITTED | 消费提交后ACK | OrderManagementIT、AsyncPurchaseIT；已测试 |
| BUCKETED隔离兼容及原桶归还 | StockBucketMapper、order_stock_allocation、升序桶锁；默认不切换 | 仍为同一交易事务及候补gate | StockBucketIT、StockBucketMigrationIT、离线切换工具；已测试，启用非本轮目标 |
| 模拟支付、流水及电子票二维码 | OrderStateService/PaymentMapper、TicketIssuer/ElectronicTicketMapper；同步生命周期 | 受控历史补票默认关闭 | ElectronicTicketIT、OrderManagementIT及浏览器；已测试 |
| 主动取消和到期关闭 | OrderStateService、库存条件UPDATE、额度条件释放 | OrderExpiryJob有界逐单、失败前进/回绕 | OrderExpiryRecoveryIT、UserSessionQuotaIT及真实浏览器截止；已测试 |
| 管理整单模拟退款及人工待办 | OrderRefundService、RefundMapper、AftercareMapper；保留支付/核销历史 | SessionAftercareJob逐单与退避 | OrderAftercareIT、StockBucketIT及浏览器；已测试 |
| 管理活动/场次/票档及取消场次 | ActivityService、SessionService、TicketService；管理锁序及版本条件 | 取消快速提交，后台善后 | ActivityManagementIT、TicketManagementIT、OrderAftercareIT及浏览器；已测试 |
| 同SKU候补申请、取消、分配与支付 | WaitlistService/Mapper；锁内ID、队首复核、OrderReservation与OrderLifecycle | InventoryConsumer、50步Dispatcher、ContinuationScheduler、Compensation | WaitlistManagementIT 100/1000队列、StockBucketIT、SchedulerTest及浏览器；已测试 |
| 工作人员授权、撤权、停用与核销 | StaffService/Mapper、ElectronicTicketService；工作人员账号SHARE协调撤权UPDATE | 最多一次UNUSED→USED，不改变交易库存 | StaffAuditIT、ElectronicTicketIT及浏览器；已测试 |
| 管理订单、售后与审计查询 | AdminOrderService/Mapper、AuditWriter/Mapper；原事务追加，V7触发器拒绝篡改 | MANUAL_REQUIRED仅查询追踪 | StaffAuditIT、OrderAftercareIT及浏览器；已测试 |
| Rabbit Confirm/Return、发布及两通道公平 | OutboxService/Mapper、OutboxPublisher；短事务SKIP LOCKED、递增令牌 | 有界在途、租约、重试、死信、数据库补偿 | OutboxPublisherTest、OutboxPipelineIT、AsyncPurchaseIT、WaitlistManagementIT；已测试 |
| 受理终态、投递耗尽和重启恢复 | PurchaseProcessing；用户/请求锁、数据库截止、条件释放预算 | PurchaseRecoveryJob；FAILED命令不复活请求 | AsyncPurchaseIT、真实故障脚本；已测试 |
| 四类后台扫描器公平性 | OrderExpiryJob、SessionAftercareJob、PurchaseRecoveryJob、WaitlistCompensation | 可靠事实在DB，内存游标仅公平性 | OrderExpiryRecoveryIT、OrderAftercareIT、AsyncPurchaseIT、WaitlistManagementIT；已测试 |
| 前端E2E/手机及桌面 | frontend/e2e/delivery.spec.mjs，真实Edge、Session/CSRF和生产dist | 原键恢复，不输出完整凭证 | 最终r9浏览器10组通过，含手机购票、订单和二维码 |
| 单实例部署、监控及CI | compose/唯一JAR、内部ADMIN Actuator、check-deployment.py | 健康/容量/预算/积压/人工待办排障 | 本地命令验收，远端Actions结果单列 |
| 真实资金、选座、实名、多租户、多实例 | 未实现 | 无 | 明确非目标 |

审查没有确认需要改变交易协议的业务Bug。新增生产代码补低基数诊断：受理账号锁/容量锁耗时、MySQL时间的Outbox创建到认领分布，以及只在JFR录制时启用的投递阶段观察。观察点位于真实提交返回及Confirm回调时刻，不承担业务联动；Rabbit回调仍只提交有界完成通知。关联头只接受与已提交事件匹配的技术标识，原始JFR留在私有目录，共享结果仅含分布和代码栈。负载脚本新增成功/业务失败/排队超时独立分布及按支付完成时刻截取的计数，旧聚合指标继续保留。没有性能优化实验或默认值调大。

## 本轮实际修改

| 文件 | 目的及实际边界 |
| --- | --- |
| `backend/src/main/java/com/ticketing/admission/PurchaseAdmission.java` | 两种受理锁耗时及提交时刻观察；保留账号UPDATE和原容量事务 |
| `messaging/DeliveryObservation.java`、`OutboxEvent.java`、`OutboxMapper.java`、`OutboxService.java`、`OutboxPublisher.java`（均位于上述Java根目录） | 增加低基数Timer、数据库时刻和JFR关联；不改变Confirm/Return、租约、递增令牌或SENT条件落账 |
| `trade/PurchaseOrderConsumer.java`、`PurchaseOrderWorker.java`（同一Java根目录） | 验证可选技术关联头，在已有认领/提交边界记录JFR；业务授权、幂等及回滚不变 |
| `backend/src/test/java/com/ticketing/waitlist/WaitlistContinuationSchedulerTest.java`、`backend/src/test/java/com/ticketing/StockBucketMigrationIT.java`、`backend/src/test/java/com/ticketing/TicketAdmissionIT.java`、`backend/src/test/resources/architecture-modules.properties` | 新增3项有界队列保护测试、1项真实CREATE VIEW拒绝测试；修正耗尽预算用例的时间假设；登记唯一新增生产类 |
| `frontend/e2e/delivery.spec.mjs`、`reporter.mjs`、`vite.config.mjs`、`frontend/playwright.config.mjs`、`frontend/package.json`及锁文件 | 最小真实Edge框架、生产dist代理、独立夹具及脱敏报告；没有替换前端设计或业务实现 |
| `scripts/upgrade-v7-v10.py`、`scripts/check-deployment.py` | 默认不升级的具体本机受控入口、私有备份校验和只读HTTP验收；不repair/clean、不提供自动改账 |
| `.github/workflows/ci.yml`、`deploy/application-operations.yml`、`.env.example` | 基础CI、已有ADMIN路径内部诊断、生产Secure默认及实际任务配置说明 |
| `AGENTS.md`、`docs/00`、`01`、`03`、`04`、`05`、`31`、`32`及新stage19结果 | 当前用法、历史属性、授权后的原库V10事实及最终证据；历史阶段报告及原始性能数据保留 |

最终首轮全量回归[失败结果](architecture/results/stage19-final-r1-tests.json)为普通90通过、真实集成256项中1项失败：`TicketAdmissionIT.exhaustedTicketDoesNotBlockSameKeyReplayOrConflictAndRejectionsAreNotAccepted`预期429而收到202，用例耗时3.166秒。旧断言假设连续HTTP调用时1令牌/秒的桶始终耗尽，慢速执行下可能合法补充，不能据此认定生产准入失效。修正仅在本例随机Redis命名空间内建立一分钟不补充的耗尽夹具，仍调用真实Lua及HTTP；独立并发预算、真实TIME补充和TTL测试保留。90普通+9真实准入定向复测通过，随后重新执行完整回归。首轮失败schema及原始报告不清理，生产实现和性能候选包不变。

## 迁移与备份恢复

[隔离V7副本最终演练](architecture/results/stage19-v7copy-r2-migration.json)使用原开发库只读备份，原始数据为6笔订单、3笔支付、3个账号。完整备份SHA-256为 `2bfe109dba1cf3bcb510eabe7058f3afd66a9be1d410bff5c1fcbd62e01f9330`，SQL仅保存在被忽略的 `.local/stage19/stage19-v7copy-r2/source-v7.sql`。

两个随机schema实际恢复后全表行数及逐行摘要与原库相同；其中一个副本停写、预检、单次批准V9、启动顺序迁移V8/V9/V10，全部原有业务列不变，所有SQL审计零偏差；撤销批准重启幂等。全新随机空库V1～V10通过，最终表、索引、约束、触发器、视图逐项归档。另一个恢复副本继续保持V7，可核对回退事实。没有在原库执行自动恢复或降级。

首轮 `stage19-v7copy-r1-migration.json` 保留：工具最初将V10新增的SINGLE/bucket_count列也计入全行摘要，使ticket_sku摘要必然不同。7行票档数量未变；改为按备份的原列逐行比较，并另查新增结构/默认值，第二轮通过。这是验收工具比较口径修正，不计为业务Bug。

迁移负向验证覆盖原有触发器权限、真实1419失败、额度超限/缺明细/溢出、V10 DDL冲突、桶归属缺失，以及本轮新增真实1142 CREATE VIEW拒绝。失败历史/部分DDL保留，不repair；重跑仍停止。原开发库随后收到用户对具体目标、备份和本次窗口的明确升级授权，已实际完成V8/V9/V10。首次启动因独立Flyway连接缺少凭据在DDL前停止；修正迁移连接后3份迁移全部成功，随后被缺失的安全限流密钥阻止启动。原电子票密钥保持，新增独立随机限流密钥仅保存在私有.env。只读验证完整V10且原业务列一致后，撤销批准完成启动与健康检查。两次失败报告及日志保留，没有失败DDL或repair。最终[原库升级及对账](architecture/results/stage19-original-r3-development-upgrade.json)、[HTTP部署检查](architecture/results/stage19-original-r3-http-smoke.json)均通过；应用随后停止，未进行生产上线。

## 浏览器、性能、部署与最终状态

本轮原始文件使用独立stage19标签，失败轮也保留。部署操作与告警说明见[单实例运维](32-single-instance-operations.md)。测试、浏览器、HTTP、性能和远端CI分别记录，不能互相替代。

最终真实故障复测共22项：[Redis及目录11项](architecture/results/catalog-faults-stage19-final.json)、[异步受理8项](architecture/results/async-faults-stage19-final.json)、[发布器崩溃3项](architecture/results/publisher-crash-stage19-final.json)均通过，后续SQL审计零偏差。覆盖Redis真实停机与恢复、Rabbit持续停机后的请求截止和预算释放、消费者PROCESSING期间真实终止进程、数据库断连同键重试、发布器在途崩溃和持久租约恢复。故障夹具显式缩短部分截止/租约或启用额外Lua保护，属于故障验证，不改变默认配置。

[最终HTTP部署检查](architecture/results/stage19-final-http-smoke.json)通过，验证真实管理员诊断身份、匿名拒绝、公开查询、Session/CSRF和中间件健康；原开发库独立检查也通过。生产TLS/反向代理及运维YAML没有在实际生产环境部署，`productionHttpsChecked=false`，不能写成生产上线成功。CI工作流只承担普通测试/架构守卫与前端严格构建，真实集成、浏览器和性能为本地隔离证据。

### 真实浏览器闭环

最终候选JAR上的[Edge结果](architecture/results/stage19-r9-browser.json)为10组通过、0失败、0跳过，运行于真实生产dist、MySQL/Rabbit/Redis和Session/CSRF，无业务响应mock。覆盖公开查询、售罄、注册与登出、刷新/越权、390px移动布局，202提交成功后真实断线并恢复，同键回放与异参409、限购、取消、支付及二维码，工作人员授权/核销/重复拒绝/撤权/停用，未用票退款与已核销人工待办，候补有效队首阻塞及分配支付，管理资源创建和库存调整，取消场次善后与审计，真实1分钟订单截止和5秒排队截止。手机宽度实际完成购票、订单支付及二维码展示，并检查无横向溢出。浏览器后[25项SQL审计](architecture/results/stage19-final-browser-audit.json)全部为0。

框架、夹具和脱敏reporter纳入源码；原始凭证、Cookie、私有账号及失败截图只在忽略目录，关闭trace/video与自动截图。失败截图遮罩输入框、二维码和票号，不发布票券。

## 最终判定及剩余边界

功能与隔离验收已完成，真实浏览器、迁移/恢复、默认可靠交易链路、故障恢复及最终346项后端回归通过。没有确认需要改交易协议的业务Bug，没有生产提速实验或默认值调整。200VU失败、冷并发登录Redis超时、长时末段变慢和一次未复现空白路由均按证据保留，不能宣称所有压力级别通过或恒定高吞吐。

原开发库本次已获明确授权并升级V10，原业务列/SQL审计零偏差、一次性批准撤销；保持初始停机状态。其他数据库的升级和生产上线没有获得本次授权。生产TLS/代理与真实生产容量未验证；默认SINGLE、额外Lua关闭、单实例单点及模拟资金边界继续保留。

实测日志中当前Flyway提示MySQL8.4新于其声明验证范围；本轮实际空库、含数据迁移及真实集成均通过，这不能代替供应方兼容承诺。未来版本升级应独立验证迁移与checksum，不为消除提示修改历史SQL或自动repair。长期容量下一步应在固定数据量和宿主I/O条件下复现末段变化，再决定是否进行单变量实验。

前三批实际已推送：`770ecc4`补充消息阶段诊断与可靠性边界测试，`ae43396`增加真实浏览器闭环与最终负载验收工具，`3a46a21`完善单实例运维检查和基础持续集成。本报告、开发计划及原始验收结果为随后独立一批，最终HEAD以该批提交及交付回复为准，未合成唯一一次推送。

运行时代码提交`3a46a21e5f8687ee5ef1eb82f40ae420c70f46ab`已在[GitHub Actions真实运行](https://github.com/WGMJJM/ticketing/actions/runs/40170951470)通过，事件为push/main、结果success；[原始CI记录](architecture/results/stage19-remote-ci-runtime.json)保留查询时间和提交SHA。最后一批仅文档/证据，其触发的最终main运行另在交付回复复核，不把此前CI冒称为尚未创建的提交结果。
