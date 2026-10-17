# 第六阶段候补购票与自动调度验收

验收日期2026-10-17。基于main现有活动、票档、Session身份、订单、模拟支付及MySQL超时关单实现；Java 21、Spring Boot 3、真实MySQL 8.4及RabbitMQ 4.1，沿用本地Docker镜像。数据库仍为V4，V1至V4未修改，无新增表、重复库存字段或无用迁移。

## 实现与关键文件

- `backend/src/main/java/com/ticketing/waitlist/controller/WaitlistController.java`：申请、本人分页列表/详情及取消，统一Session、最新账号检查和CSRF。
- `waitlist/service/WaitlistService.java`、`WaitlistDispatcher.java`、`mapper/WaitlistMapper.java`：真实库存不足申请、有效重复请求、队首重查、FIFO分配及资格失效终结。
- `order/service/OrderService.java`：共用库存预占和价格快照；WAITLIST_CONFIRM域、候补唯一键；支付/取消/超时与候补状态同事务联动，关联失配整体回滚。
- `ticket/service/TicketService.java`、`waitlist/messaging/InventoryEventWriter.java`：库存释放、管理员增库存及入队/退出提示写同事务Outbox。
- `waitlist/messaging/OutboxService.java`、`OutboxPublisher.java`、`mapper/OutboxMapper.java`：数据库认领、租约令牌、持久消息、发布确认、退避与FAILED保留。
- `waitlist/messaging/WaitlistRabbitConfig.java`、`InventoryConsumer.java`：持久交换机/队列与死信，消息数据库事实校验，提交后确认、有界消费重试。
- `waitlist/service/WaitlistCompensation.java`、`WaitlistBackgroundJob.java`及`config/TaskSchedulingConfig.java`：有界低频扫描、失败隔离、重启读库与调度线程池。
- `frontend/src/api/waitlist.ts`、`components/WaitlistPanel.vue`、`views/WaitlistView.vue`：真实申请、确认、列表/详情、取消、刷新与关联订单入口；导航、个人中心、路由及平台说明同步更新。
- `backend/src/test/java/com/ticketing/waitlist/messaging/WaitlistManagementIT.java`：25项真实MySQL/RabbitMQ测试；原有测试定向清理新增Outbox，最新账号过滤测试覆盖候补路径。

## FIFO与事务协议

统一READ_COMMITTED，锁序为用户UPDATE→活动SHARE→场次SHARE→SKU UPDATE→订单UPDATE→候补更新。入队在SKU锁内生成自增候补ID并持有至提交；多个用户不能先生成低ID却在后续候补之后才提交。分配先无锁发现队首用户，再按相同锁序加锁，锁内重新查WAITING最小ID。候选已变化则释放全部锁后重选，不持有SKU去锁下一用户；多个消费者在同一SKU行串行化。

队首数量不足即停止，不向后分配。过期、禁用、父级失效、销售窗口结束或限额不足的队首转EXPIRED后继续。WAITING不占库存/场次额度；入队检查即时额度，分配再次在用户行锁内检查PENDING/PAID合计，跨SKU共享默认4张限额。库存足够应直接购票。有效重复申请同数量返回原记录，不同数量409；有效唯一键最终兜底。

分配复用订单原有条件预占SQL、服务端金额乘算和明细快照，固定WAITLIST_CONFIRM域与waitlist-{候补ID}键；库存、订单和ALLOCATED同时提交。用户、场次、SKU和数量均检查，不以现有单表约束代替跨表关系。付款→COMPLETED，预留单取消/超时→EXPIRED，和订单、库存同事务；重复消息/支付/关单无重复副作用。后台锁用户但不要求ACTIVE，禁用账号的旧预留单仍会释放。

FIFO只保证同票档候补内部顺序，普通购票仍可竞争库存。等待截止为数据库时间加配置时长（默认60分钟）与SKU停售的较早值；分配后以订单独立支付截止为准（默认15分钟）。

## 消息确认、重试与补偿

库存释放、管理员增库存与Outbox同事务，提交前不发MQ。发布任务逐条用SKIP LOCKED认领，PROCESSING租约默认30秒，retry_count递增作为认领令牌；旧发布器不能覆盖租约恢复后的新认领。持久消息经correlated confirm且无mandatory退回才转SENT，确认超时5秒。失败从2秒指数退避至最多300秒，总尝试默认10次后FAILED；崩溃/确认丢失允许再次投递。

消费者先核对messageId、eventId、SKU和已提交事件；每笔分配事务提交后方法才返回并确认消息。部分成功后的失败重投只继续剩余WAITING。默认最多4次消费、等待1/2/4秒，耗尽后拒绝重新入队并进持久死信；不采用快速无限重入队。可靠语义为至少一次投递与数据库幂等，不宣称端到端Exactly Once。

独立数据库补偿每60秒扫描最多100个WAITING SKU，每SKU最多50步；无需MQ通知也可恢复，零库存队首过期也可终结。失败SKU不阻塞后续，游标到尾回绕；重启重新读库，游标不承载业务事实。FAILED事件及死信保留用于定位，业务补偿不依赖将它们伪装为成功。

## 自动化验证

| 命令或检查 | 实际结果 |
| --- | --- |
| `mvn -f backend/pom.xml clean verify` | 35项基础测试，失败0、错误0、跳过0 |
| `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/start-backend.ps1 -VerifyDatabase` | 35项基础测试及84项真实集成测试，共119项，失败0、错误0、跳过0 |
| 原有集成回归 | 活动6、认证13、迁移5、应用重启关单1、订单/支付25、Cookie1、票档8，共59项全部通过 |
| 新增候补集成测试 | 25项全部通过，真实HTTP Cookie/Session/CSRF、MySQL独立事务及真实RabbitMQ |
| `cd frontend; npm ci; npm run build` | 安装成功，严格TypeScript检查及Vite生产构建成功；页面文案最终调整后重建成功 |
| Flyway与Git检查 | V1至V4校验通过且文件未改，`git diff --check`通过，README/凭据/产物仍忽略 |

新增25项覆盖正常申请/取消、并发重复申请与异参、非法数量/身份字段/销售窗口、本人归属/匿名/CSRF/禁用会话、ABC顺序与金额快照、队首不足阻塞、并发真实HTTP入队与多调度器竞争、旧队首重查、跨SKU限额资格变化、禁用/过期/活动下架/场次取消、分配失败全回滚、预留订单取消/超时联动与禁用账号释放、重复支付一次完成、消费者停止恢复后的完整消息闭环、重复真实消息、多消费者幂等、连接失败退避恢复、确认丢失后重投、租约与新发布器实例恢复/旧令牌拒绝、并发认领互斥、投递耗尽后的数据库补偿、临时消费重试、持续消费失败进死信后补偿、库存与Outbox写入失败原子回滚、错误预留关联拒绝支付，以及普通购票/候补/取消/支付/过期竞争后的数据库守恒。

故障注入只在测试Spy中实现，库存、订单、支付和消息队列均是真实外部资源；故障后直接核对数据库终态、库存和流水数量。确认丢失测试先实际发出消息再注入等待失败；租约恢复测试更换发布器实例，并非宣称测试了主机断电。应用完整重启回收仍由既有OrderExpiryRecoveryIT验证。消息IT使用独立.it交换机与队列，不清空业务队列；共用开发库时Outbox可读到其他业务遗留事件，不能把测试队列总消息数当作单笔订单事实。

## 浏览器真实联调

启动真实Spring Boot和Vite，通过浏览器注册用户B，并使用已有用户A。演示活动900562、场次900488、SKU900479仅创建初始演示资料，名称明确标记演示；订单、候补、取消、分配及支付全由真实页面和API完成，没有SQL伪造业务终态。

1. B选择候补体验票1张、单价¥188.00，确认创建PENDING订单900393，最后1张库存被预占。
2. A看到已售罄与剩余0，确认申请1张候补206，状态WAITING；刷新页面仍恢复真实会话及候补。
3. B重新登录并确认取消900393，MySQL释放库存，同事务写ORDER_CANCELLED事件378；事件随后SENT，RabbitMQ业务队列正常消费（2个消费者，无积压/死信）。
4. A刷新“我的候补”看到ALLOCATED及真实预留订单900394，操作域WAITLIST_CONFIRM；数据库此时total=1、locked=1、sold=0。
5. A经关联订单核对活动、场次、票档、数量及¥188.00金额，确认模拟支付；订单PAID，真实唯一流水20e05dd9157e4c4e82b1d5fd007c8b7b，金额18800分。
6. A返回候补详情，显示COMPLETED及已支付订单入口。数据库最终total=1、locked=0、sold=1、available=0，订单与流水金额一致，入队及取消事件均SENT。

最终回归后再次启动真实后端，旧内存Session失效并正确跳转登录；重新登录自动返回/waitlist/206，数据库中的COMPLETED及已支付关联保持不变。

本地证据位于被忽略的 `.local/waitlist-waiting.png`、`waitlist-allocated.png`、`waitlist-completed.png`、`waitlist-browser-allocated-db.tsv`、`waitlist-browser-completed-db.tsv`；不提交截图中的本地账号、会话或生成凭据。最终测试日志为 `.local/waitlist-acceptance-it.log`，构建日志为 `.local/waitlist-acceptance-frontend.log`。

## 交付边界

已完成第六阶段候补申请、FIFO、订单状态联动、Outbox、RabbitMQ及数据库补偿；README只留本地，main按功能提交后推送，不强推。不开发真实支付、退款、电子票或核销，不进行前面讨论的结构重构。不保证即时分配、优先于新购票或未经压测的抢票能力。
