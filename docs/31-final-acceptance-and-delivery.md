# 第十九阶段最终验收与交付

执行基线为 `9830a2658bd9789a64a91ffa6702a74a1b487c3c`。开始时 fetch 后本地/远端 main 相同，工作区干净。最终代码以本报告所属提交及分批交付历史为准，冻结JAR、源码摘要和实测文件在下文逐项关联；历史阶段数量和原始性能结果不改写。

## 范围、基线与审查方式

源码仍为一个 Java21 / Spring Boot3 模块，167个生产类按业务包登记，唯一应用为 `TicketingApplication`。默认SINGLE、额外Lua准入关闭、消费者2/prefetch1、Outbox窗口16、Hikari最大10、持久受理容量200。所有普通订单通过真实HTTP202/请求/Outbox/Rabbit/消费者链路。没有新增迁移、生产中间件或第二个后端服务。

实测主机为Windows11 build26200、i5-13400F/16逻辑处理器、31.82GiB内存；Docker29.5.2分配16CPU/约15.53GiB。运行Java21.0.10、Maven3.9.9、Node25.9.0，复用MySQL8.4、Rabbit4.1和Redis7镜像，镜像摘要及具体版本见[硬件](architecture/results/stage19-candidate/hardware.json)和[运行快照](architecture/results/stage19-candidate/runtime.json)。专用单JVM为`-Xms512m -Xmx1024m -XX:+UseG1GC`，排队截止120秒、消息TTL120000毫秒、发布租约30秒/确认超时5秒。原开发Compose停止，性能阶段只运行固定perf中间件和唯一perf后端；没有拉取更新镜像。

专用perf还沿用1分钟支付窗口、1秒过期扫描，普通部署默认分别为15分钟和30秒；浏览器截止用例显式切换短窗口，候补退款夹具为15分钟。这些测试配置与生产默认区别保留在脚本及结果中，不能把“默认SINGLE/窗口16/消费者2/池10”扩大解释为所有运行参数均与生产相同。

[开始快照](architecture/results/stage19-start/source-inventory.json)读取并索引全部生产/测试/前端、SQL、配置、脚本和部署源码，记录文件SHA、入口及事务标记；人工重点审查受理/恢复、交易锁序、退款/核销、库存分桶、消息流水线、四类扫描器和前端身份/恢复/凭证生命周期。自动清单不等于逐行人工证明。`docs/01`～`05`和`25`～`30`用于对照，旧报告只作为历史证据。

本轮先执行普通 `mvn clean verify` 和前端 `npm ci`/严格构建，再执行 [完整基线](architecture/results/stage19-baseline-tests.json)：87项普通、255项真实集成，共342项，失败/错误/跳过均0。这是本轮运行结果，未继承阶段18通过数。新增队列溢出/失败隔离/关闭及V10缺CREATE VIEW测试后，[定向回归](architecture/results/stage19-target-tests.json)为90项普通和24项真实集成，全部通过。

最终第二轮完整回归于2026-10-25完成：90项普通、256项真实MySQL/Rabbit/Redis集成，共346项，失败/错误/跳过均0；8分26秒。独立普通命令90项通过，前端`npm ci`零漏洞、`npm run build`严格类型及生产构建通过，39个dist文件与最终浏览器使用的包逐文件摘要一致。隔离守卫7项、登录函数4项、JFR解析3项及37份Python语法检查通过；这些脚本检查与浏览器组数不混入346项后端计数。[最终机读汇总](architecture/results/stage19-final-validation.json)保留套件、命令、实际SHA和范围。

| 实际执行命令 | 最终结果与证据 |
| --- | --- |
| `mvn -f backend/pom.xml clean verify` | 90项通过；`.local/stage19/final-ordinary-backend.log` |
| `python -u -B scripts/performance/verify_backend.py --label s19final2` | 90普通+256真实集成全部通过；`.local/stage19/final-integration-r2-backend.log` |
| 前端目录`npm ci`、`npm run build` | 安装审计0漏洞，严格构建通过，39个dist摘要一致 |
| `TICKETING_E2E_LABEL=stage19-r9`，前端`npm run e2e` | 真实Edge 10组通过，最终浏览器报告与审计独立归档 |
| `python -B scripts/performance/test_safety.py`、`node scripts/performance/test_login.mjs`、`python -B scripts/performance/final_jfr.py` | 7/4/3项全部通过；不代替真实业务集成 |

普通与集成命令会重复执行普通测试，346是最终完整profile中的用例数，不把重复运行叠加为新增业务覆盖。保留首轮失败和定向复测。V1～V10没有新增或改写，最终源码快照同时索引部署、CI、前端E2E及关键根配置。

## 功能与一致性映射

以下路径以 `/api/v1` 为前缀。最终执行计数及结果汇总见同目录 `stage19-final-validation.json`，浏览器与HTTP/JUnit分别记录。

| 用户功能及入口 | 服务与数据库/事务 | 异步与恢复 | 测试定位/分类 |
| --- | --- | --- | --- |
| 首页/活动详情；GET activities、sessions、tickets | ActivityService/SessionService/TicketService；Activity/Session/TicketSkuMapper；公开缓存及实时库存 | CatalogCache提交后换代 | ActivityManagementIT、TicketManagementIT、CatalogCacheIT；已测试 |
| 注册、登录、登出、个人中心；auth/* | AccountService/UserAccountMapper、BCrypt、SecurityConfig、CurrentAccountFilter | Redis限流、实时账号角色校验 | AuthenticationIT、CsrfSessionIT、TrustedProxyIT、RateLimitIT；已测试 |
| 选票→202抢票请求→本人恢复；POST orders、GET purchase-requests/lookup及详情 | PurchaseEntryService/PurchaseAdmission/PurchaseRequestMapper；账号UPDATE、唯一键、预算事务 | PurchaseCommands/Outbox、PurchaseOrderConsumer、PurchaseRecoveryJob | AsyncPurchaseIT及浏览器；已测试 |
| 容量/重复同键/异参409/过载503 | purchase_capacity与非终态请求严格相等；失败回滚，不捕获唯一键后继续 | 恢复截止、令牌与容量条件释放 | AsyncPurchaseIT；250人突发200受理/50容量拒绝及恢复通过；200VU阶梯未通过 |
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
| MySQL空库、历史升级及失败现场 | MigrationSafetyConfig、V1～V10、只读审计、备份 | 不自动repair/clean | MigrationSafetyIT、QuotaMigrationIT、StockBucketMigrationIT、final_migration.py；已测试 |
| Redis异常与只读目录降级 | RedisRateLimiter/TicketAdmissionLimiter/CatalogCache；关键写失败关闭 | L1有界、受控回源、恢复填充 | CatalogCacheIT、RateLimitIT、catalog_faults.py；已测试 |
| 四类后台扫描器公平性 | OrderExpiryJob、SessionAftercareJob、PurchaseRecoveryJob、WaitlistCompensation | 可靠事实在DB，内存游标仅公平性 | OrderExpiryRecoveryIT、OrderAftercareIT、AsyncPurchaseIT、WaitlistManagementIT；已测试 |
| 前端E2E/手机及桌面 | frontend/e2e/delivery.spec.mjs，真实Edge、Session/CSRF和生产dist | 原键恢复，不输出完整凭证 | 最终r9浏览器10组通过，含手机购票、订单和二维码 |
| 单实例部署、监控及CI | compose/唯一JAR、内部ADMIN Actuator、check-deployment.py | 健康/容量/预算/积压/人工待办排障 | 本地命令验收，远端Actions结果单列 |
| 单实例容量及长时稳定性 | final_load_suite.py、final_overload.py、真实SQL审计 | 反序双轮、阶梯、突发、30分钟及真实故障 | 双轮、25/50/100阶梯、突发、1800秒通过；200VU失败及末段变慢均保留 |
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
| `scripts/performance/final_snapshot.py`、`final_migration.py`、`final_browser_fixture.py`、`final_load_suite.py`、`final_overload.py`、`final_waitlist_refund.py`、`final_jfr.py` | 快照、隔离迁移、真实浏览器夹具、反序/阶梯/长时、容量突发、候补退款与JFR证据；唯一标签防覆盖 |
| `scripts/performance/perf.py`、`scenarios.js`、`metric_quantiles.py` | 完成时间口径、失败独立分布、低基数采样及逐一登录准备；旧指标仍保存 |
| `scripts/upgrade-v7-v10.py`、`scripts/check-deployment.py` | 默认不升级的具体本机受控入口、私有备份校验和只读HTTP验收；不repair/clean、不提供自动改账 |
| `.github/workflows/ci.yml`、`deploy/application-operations.yml`、`.env.example` | 基础CI、已有ADMIN路径内部诊断、生产Secure默认及实际任务配置说明 |
| `AGENTS.md`、`docs/00`、`01`、`03`、`04`、`05`、`31`、`32`及新stage19结果 | 当前用法、历史属性、授权后的原库V10事实及最终证据；历史阶段报告及原始性能数据保留 |

确认的业务Bug修复数为0；测试等待、选择器、比较口径和负载准备问题按失败记录修正，不冒充业务成果。新增本机安全限流密钥只在忽略的`.env`，原票券密钥保留，原始SQL备份/JFR/账号/日志在`.local`；根README仍忽略，不提交构建产物。

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

### 负载工具启动失败记录

`stage19-baseline-r1`在100VU热点场景失败：并发登录与业务加压交叠，27次登录失败和1次购票503，共28次服务器错误。日志确认Redis安全校验`QueryTimeoutException`，没有绕过失败关闭；业务稳定窗口非预期错误为0，最终860笔受理全部SUCCEEDED，25项审计零偏差，新增死锁/锁超时均0。该轮不能记为通过。

`stage19-baseline-r2`增加20秒登录隔离窗口，仍有4次建会话阶段503，日志同样为Redis校验超时；稳态业务错误为0，整轮仍判失败。仅凭日志无法证明具体CPU停顿或Redis网络根因，不将推测写成确认Bug。

随后最终负载使用`preauthenticate`：在k6 setup内逐一进行真实登录，为每个VU保留独立会话；登录失败立即使运行失败，没有自动重试。Cookie/CSRF只保存在k6内存，summary明确删除setup_data，不输出认证响应。场景仍有10秒业务预热和固定稳态窗口，所有最终对照使用相同脚本。该变更只隔离夹具准备，不修改应用500ms Redis超时、安全限流或业务参数。前两轮完整原始聚合文件继续保留，不能据此宣称并发登录已得到性能优化。

### 真实浏览器闭环

最终候选JAR上的[Edge结果](architecture/results/stage19-r9-browser.json)为10组通过、0失败、0跳过，运行于真实生产dist、MySQL/Rabbit/Redis和Session/CSRF，无业务响应mock。覆盖公开查询、售罄、注册与登出、刷新/越权、390px移动布局，202提交成功后真实断线并恢复，同键回放与异参409、限购、取消、支付及二维码，工作人员授权/核销/重复拒绝/撤权/停用，未用票退款与已核销人工待办，候补有效队首阻塞及分配支付，管理资源创建和库存调整，取消场次善后与审计，真实1分钟订单截止和5秒排队截止。手机宽度实际完成购票、订单支付及二维码展示，并检查无横向溢出。浏览器后[25项SQL审计](architecture/results/stage19-final-browser-audit.json)全部为0。

此前失败轮按原标签保留：r1等待停用UI写入完成不足；r2切换管理员前未退出旧会话；r4在SPA路由完成前读取票ID；r5使用英文关闭按钮定位实际中文组件；r6排队过期文案匹配到两个节点。均修正测试等待或定位后重跑。r3曾出现一次空白懒加载路由，未捕获到可证实根因；补充错误捕获后r7及最终JAR的r8/r9均未复现，作为残余观察保留，不宣称修复了产品Bug。r7是新增JFR前候选包，r8首次完整通过最终JAR，最终计数采用补充手机检查后的r9。

框架、夹具和脱敏reporter纳入源码；原始凭证、Cookie、私有账号及失败截图只在忽略目录，关闭trace/video与自动截图。失败截图遮罩输入框、二维码和票号，不发布票券。

### 同JAR反序双轮

最终候选JAR SHA-256为`690448e32c25353f060b00ce5fd09d12070347f42f1affbb555e14d043ad7345`。[正序r3](performance/results/stage19-baseline-r3/comparison.json)和[反序r4](performance/results/stage19-baseline-r4/comparison.json)使用相同JAR、脚本及默认业务参数。原安全限流保持开启，但专用`regression`模式将登录/注册/订单等阈值放宽为10000，额外票档Lua准入关闭；不是生产默认保护阈值的容量承诺。每场1000活动，热点1场次、混合64场次；10秒业务预热后固定60秒稳态，按客户端请求或终态查询完成时刻截取。受理TPS只数首次提交202，成功订单TPS只数SUCCEEDED终态，支付TPS按支付响应完成时刻计数；HTTP RPS包括业务提交/回放/轮询/支付/取消，不包括登录准备与Python监控请求。

| 场景及真实买家数 | 轮次 | HTTP RPS | 受理TPS | 成功订单TPS | 支付TPS | 成功终态P50/P95/P99毫秒 |
| --- | --- | ---: | ---: | ---: | ---: | --- |
| hot-ample 100VU/100账号 | r3 | 362.75 | 12.78 | 12.77 | 0 | 7124 / 15786.75 / 18601.20 |
| hot-ample 100VU/100账号 | r4 | 365.20 | 12.73 | 12.68 | 0 | 7116 / 15247 / 18010 |
| hot-scarce 100VU/100账号 | r3 | 365.83 | 13.37 | 12.53 | 0 | 6775.50 / 14672.80 / 18150.94 |
| hot-scarce 100VU/100账号 | r4 | 384.93 | 13.72 | 12.98 | 0 | 6884 / 9072.70 / 9383.10 |
| mixed 25VU/25账号 | r3 | 119.72 | 15.57 | 15.62 | 5.15 | 1528 / 1913.20 / 2516.44 |
| mixed 25VU/25账号 | r4 | 118.82 | 15.08 | 15.05 | 5.00 | 1616 / 2029.90 / 2406.62 |
| same-user-cross-session 25VU/1账号 | r3 | 113.35 | 15.73 | 8.73 | 3.12 | 1491 / 2073.85 / 2294.08 |
| same-user-cross-session 25VU/1账号 | r4 | 111.22 | 15.07 | 8.30 | 3.02 | 1637.50 / 2200.55 / 2694.60 |

八轮k6全部通过，5xx/传输错误/未知409/非预期错误均0；新增死锁、锁超时均0，25项审计全部0，最终Outbox排空。稀缺库存稳态业务失败为47/43笔，失败P95为13292.10/8911毫秒；单用户场景失败423/404笔，失败P95为1776.30/1935.50毫秒，主要对应限购事实。该场景混有额度拒绝，不能把订单TPS差异全部归因于账号锁。无排队截止样本的分布为空，不能写成0毫秒。

Hikari上限10，热点场景采样active最高10、pending峰值12～70；四组场景获取超时计数均0。热点持久排队峰值97～98，未触及200上限。正序充足库存热点JFR全轮观察显示：受理提交→Outbox认领P95约10410毫秒，认领→Confirm约19.67毫秒，Confirm→消费者认领约5651毫秒，消费者认领→订单提交约192毫秒。各段是不同样本集合、含重投且分布重叠，不能直接相加；原始分布与样本数见`delivery-stages.json`。

SQL digest及锁采样定位到SKU、持久容量和逐条Outbox落账等等待，不能由CPU火焰图推算锁持有时长。JFR CPU采样覆盖登录准备和整轮，不能将登录BCrypt热点当作稳态购票瓶颈。重试次数大于1也不等同于代理故障：等待超过恢复间隔的QUEUED可将SENT命令重投，最终仍由数据库幂等。该现象与本轮部分重复投递相符，缺失阶段关联按incomplete计数保留。没有做生产性能优化或调整默认值，不宣称相对第18阶段提速。

### 阶梯边界

[阶梯原始结果](performance/results/stage19-steps-r1/comparison.json)每级使用与VU相同数量的独立买家，10秒预热和45秒稳态。25/50/100VU分别通过，成功建单13.71/13.04/14.58 TPS，成功终态P95为2471/6655.50/15944.50毫秒。它们是短窗口的阶梯观测，长期结果另列。

200VU整轮**未通过**，停止进一步加压。k6记录预热开始时9次`connectex`连接拒绝，状态0；无登录失败、5xx或未知409，稳态非预期错误为0。应用日志没有对应重启，观察到的是TCP接入失败，不能据此断言唯一socket根因。该级稳态成功订单7.91 TPS、受理7.87 TPS、业务HTTP 560.67 RPS，成功P50/P95/P99为23841.50/29086/30698.80毫秒。这些数值只描述失败轮，不能当作通过的可持续容量。

该轮最终600笔请求均SUCCEEDED，1200条Outbox均SENT，预算恢复，25项SQL审计0，新增死锁/锁超时及Hikari获取超时0。原始[k6](performance/results/stage19-steps-r1/hot-ample-200/hot-ample-200/k6-summary.json)、[数据库和环境](performance/results/stage19-steps-r1/hot-ample-200/hot-ample-200/environment.json)及JFR全部保留；没有扩大连接池、线程数或放宽错误阈值来改判结果。

### 持久容量突发及恢复

[250名独立买家突发](performance/results/stage19-overload-r1/overload.json)使用真实会话、250个同时提交工作线程，期间消费者和发布器明确暂停。5.781秒内完成全部HTTP提交：200个202、50个503 `PURCHASE_QUEUE_FULL`、0其他结果。有限批次HTTP约43.25次/秒、受理34.60次/秒；暂停期间正式订单TPS为0，不能拿受理数字充当建单吞吐。

只用正常重启恢复，包含应用启动的恢复排空耗时25.391秒，200笔最终SUCCEEDED，容量余额恢复200。暂停及恢复后的审计均零偏差。没有手动归零预算、改订单状态或清队列来恢复。

### 候补、模拟退款与取消交织

[交织结果](performance/results/stage19-waitlist-refund-r1/waitlist-refund.json)为100名独立候补和100名独立原订单持有人。先完成50笔模拟支付，再用真实HTTP并发退款50笔、取消50笔，同时候补查询、分配并支付。有限批次7.625秒内100名候补全部COMPLETED，关联订单ID按候补ID升序，FIFO通过；新增死锁/锁超时0，监测失败0，全部SQL审计零偏差。

有限批次完成并支付约13.11笔/秒，只描述该批处理，不当作预热后稳定成功订单TPS。原50笔付款在退款后仍保留，票券作废、库存和额度释放由原同步交易协议完成。

### 30分钟稳定性

[长时原始结果](performance/results/stage19-soak-r1/comparison.json)实际完成1800秒稳态，25VU/25独立账号、热点充足库存、真实202→消费者建单→取消闭环。同一候选JAR和配置，稳态24724笔成功订单，受理/成功建单均13.7356 TPS，业务HTTP117.895 RPS，成功端到端P50/P95/P99为1616/2405/2675毫秒，最大3311毫秒。业务失败及排队截止无样本；5xx、传输错误、登录错误、非预期错误均0。

包含预热和收尾共24864笔订单、49728条Outbox，最终请求全SUCCEEDED、Outbox全SENT、预算释放；25项SQL审计全部0，新增死锁/锁超时0。全轮779个监测样本（含准备、预热和收尾）中，容量占用峰值25，Outbox最老年龄峰值4.12秒；Hikari active最高10、pending最高4、获取等待最大观察值202.26毫秒、获取超时0。堆占用约48.7～352.1MiB，末次采样约107.7MiB，GC暂停最大观察值15毫秒，线程最高104、末次采样103；CPU使用率归一值峰值0.313，末次采样0.034。

本轮没有观察到无限积压或堆持续增长，但30分钟和有限采样不能证明不存在内存泄漏，也不是长期生产SLA。该负载主动取消订单、支付TPS为0；支付及退款吞吐分别由混合场景和有限交织批次说明，不将三者混算。

进一步[首尾窗口观察](architecture/results/stage19-soak-window-observation.json)显示阶段性变慢：排除首尾各60秒，比较约300秒服务端Timer差分，前段建单约14.34次/秒、交易均时93.96毫秒，末段约10.65次/秒、均时124.21毫秒；SKU锁调用均时51.16→70.23毫秒、容量锁5.38→12.42毫秒，受理到认领均时1362.99→1903.42毫秒。连接获取均时反而0.082→0.046毫秒，进程CPU归一均值0.047→0.029。观察与数据库锁/写入等待增加相符，不能简单归因于连接池或CPU。

这是服务器采样差分，不能替代k6客户端完成口径或其P95/P99。未进行固定数据量/磁盘状态的独立A/B，因此不能确认数据增长、宿主资源波动或某条SQL是唯一根因。13.74 TPS只是全程平均值，不宣称30分钟内吞吐恒定，也不保证继续运行保持该速度。该退化观察作为容量限制保留，没有通过重置中途数据、扩大池或删除慢样本掩盖。

## 最终判定及剩余边界

功能与隔离验收已完成，真实浏览器、迁移/恢复、默认可靠交易链路、故障恢复及最终346项后端回归通过。没有确认需要改交易协议的业务Bug，没有生产提速实验或默认值调整。200VU失败、冷并发登录Redis超时、长时末段变慢和一次未复现空白路由均按证据保留，不能宣称所有压力级别通过或恒定高吞吐。

原开发库本次已获明确授权并升级V10，原业务列/SQL审计零偏差、一次性批准撤销；保持初始停机状态。其他数据库的升级和生产上线没有获得本次授权。生产TLS/代理与真实生产容量未验证；默认SINGLE、额外Lua关闭、单实例单点及模拟资金边界继续保留。

实测日志中当前Flyway提示MySQL8.4新于其声明验证范围；本轮实际空库、含数据迁移及真实集成均通过，这不能代替供应方兼容承诺。未来版本升级应独立验证迁移与checksum，不为消除提示修改历史SQL或自动repair。长期容量下一步应在固定数据量和宿主I/O条件下复现末段变化，再决定是否进行单变量实验。

源码、工具和运维配置按功能分批提交并依次推送main，使用已确认作者，根README、凭据、原始备份/JFR及构建产物不提交。CI远端结果以本轮推送后的Actions记录为准；工作流只验证普通/架构与前端构建，不标成346项真实集成的云端执行。

前三批实际已推送：`770ecc4`补充消息阶段诊断与可靠性边界测试，`ae43396`增加真实浏览器闭环与最终负载验收工具，`3a46a21`完善单实例运维检查和基础持续集成。本报告、开发计划及原始验收结果为随后独立一批，最终HEAD以该批提交及交付回复为准，未合成唯一一次推送。

运行时代码提交`3a46a21e5f8687ee5ef1eb82f40ae420c70f46ab`已在[GitHub Actions真实运行](https://github.com/WGMJJM/ticketing/actions/runs/40170951470)通过，事件为push/main、结果success；[原始CI记录](architecture/results/stage19-remote-ci-runtime.json)保留查询时间和提交SHA。最后一批仅文档/证据，其触发的最终main运行另在交付回复复核，不把此前CI冒称为尚未创建的提交结果。
