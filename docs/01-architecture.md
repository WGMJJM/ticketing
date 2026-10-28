# 架构与模块协作

本文描述当前源码。2026-10-27 候补已复用统一购票链路；2026-10-26 的普通购票重构替代旧 MySQL 受理、购票 Outbox、处理租约和 Redis 冻结重建协议；阶段验收文档（如 25、27、33、34）保留为历史记录。

## 项目结构

Java 21、Spring Boot 3、MyBatis-Plus、MySQL、Redis、RabbitMQ、Spring Security Session，保持前后端分离的模块化单体。后端只有 `backend/pom.xml` 一个 Maven 模块和唯一 `TicketingApplication`，根包 `com.ticketing`。

技术包为 `controller`、`service`、`mapper`、`entity`、`dto`、`vo`、`config`、`enums`、`security`、`task`、`messaging`、`cache`、`exception`、`common`。请求/查询参数归 DTO，响应归 VO，定时任务归 task；不为目录形式新增空接口。`architecture-modules.properties` 登记逻辑业务归属，架构测试继续检查技术分层、归属完整、业务依赖无环与交易公开协作边界。

## 普通购票链路

```mermaid
flowchart LR
    H[普通购票与安全限流] --> P[PurchaseAdmission.submit]
    W[候补持久 FIFO 队首] --> P
    P --> R[同一 Redis Lua 原子预扣]
    R --> Q[RabbitMQ 直接发布]
    Q --> A[Confirm ACK 且未退回后 HTTP 202]
    Q --> C[消费者执行 MySQL 本地事务]
    C --> O[库存及额度条件更新、订单和申请终态提交]
    O --> S[Redis 原子结算]
    S --> K[成功返回后 MQ AUTO ACK]
    R --> J[唯一 PurchaseRecoveryJob]
    J --> Q
    J --> E[MySQL 过期仲裁或补齐终态结算]
```

HTTP `POST /api/v1/orders` 由 `PurchaseAdmission` 编排，只调用 Redis 和 RabbitMQ，没有购票申请 SQL、MySQL 受理事务或购票 Outbox。既有安全过滤器仍按数据库最新账号状态鉴权，并执行 CSRF 与独立请求限流。requestId 由服务端生成；Lua 首先检查用户、固定 CREATE_ORDER 域和幂等键，同参重放复用原 ID，异参冲突，不重复预扣。

Lua 一次完成销售资格、Redis 时间窗口、单笔数量、场次内用户未完成预扣数量、库存和排队容量检查，之后扣减库存，写 RESERVED 凭据与恢复 ZSET。入口预检只限制并发未完成申请；已建订单的精确场次限额仍由消费者在 MySQL 中校验，不宣称 Redis 是额度权威。

`PurchasePublisher` 使用持久消息、durable 队列、mandatory 和 CorrelationData Confirm。默认最多64个在途发布，多个请求可并行确认，回调不访问数据库或Redis。HTTP仅在ACK且未退回时返回202；窗口满、发送失败或确认超时返回受控503，保留原RESERVED，由原幂等键查询/重试以及恢复任务解决。确认未知不等于消息未投递，绝不据此返票。RabbitMQ不可用时没有同步MySQL下单降级。

## MySQL 消费与三道幂等

2026-10-28本次普通分桶购票扩展为4桶或8桶。首次路由使用`StockBucketRouter.route(requestId,bucketCount)`，以MurmurHash3的64位finalizer混合原申请ID，再掩码映射4/8桶，避免直接读取雪花ID低位；正常路径只有固定整数运算，不增加I/O、锁或对象分配。跨桶路径仍在整笔回滚后按桶号升序获取X锁；库存快照与销售启用校验同步允许4/8桶。已提交订单只按不可变allocation履约，不因路由变化重分配；排队及恢复仍使用原requestId与幂等协议。不改迁移或自动切换业务SKU，分桶新候补仍禁用。下文四桶实现记录为此前阶段事实。

2026-10-28 新增四桶普通购票，完整实现与隔离验收见[四桶库存实现](jmeter/four-bucket-implementation.md)。V10 表结构直接复用，未新增迁移或自动切换业务 SKU。BUCKETED=4 按原 requestId 选择初始桶；SKU 配置用 SHARE，目标桶条件 UPDATE 获得独立 X 锁，不更新主 SKU 交易计数。单桶不足先整笔回滚，再按桶号升序跨桶，订单、明细、不可变 allocation 和申请终态原子提交。SINGLE 仍使用原 SKU UPDATE 锁。分桶新候补暂不开放，历史订单与 SINGLE 候补继续按原生命周期处理。

库存同步以 SKU X 与普通分桶 SKU S 互斥，在 pause/pending 排空之后校验并汇总四桶，再发布绝对 Redis 可售余额。Redis 命令默认超时为 1s，连接超时为 500ms，允许环境变量覆盖；AOF always 不变。锁冲突的三次有界重试发生在完整事务之间，重试耗尽保留原申请与预扣，不转换为无票失败。

`PurchaseOrderWorker` 从 Redis 凭据读取可信业务参数，以 requestId 唯一键插入/锁定 `purchase_request`，与过期仲裁共享同一个数据库互斥协议。随后按原资源锁序调用 `TradeResourceLocker` 和 `OrderReservation`；MySQL 库存条件更新、场次额度、订单和价格明细快照、SUCCEEDED/orderId 在一个 READ_COMMITTED 本地事务提交。请求行 QUEUED 只是事务内协调占位，不独立提交为排队事实，没有 processing token、租约或第二条普通建单路径。

确定业务错误先使整个建单事务回滚，再重新锁同一 requestId 登记 FAILED/EXPIRED；若重复消费者或仲裁已经先提交，采用其原终态。数据库连接、死锁等技术故障不记为确定业务失败，交给有限延迟重试及统一补偿。消费返回后的 AUTO ACK 发生在 SQL 提交之后；Redis 结算暂时失败可先 ACK，由恢复索引补齐。重复消息不会再次占库存或创建订单。

1. Redis 入口：用户 + 服务端操作域 CREATE_ORDER / WAITLIST_CONFIRM + 幂等键，验证请求摘要并复用 requestId。
2. MySQL 消费：requestId 主键、用户/操作域/幂等键唯一约束、订单关联唯一约束和本地事务。
3. Redis 结算：RESERVED 只能一次转为 CONSUMED 或 RELEASED。成功不再次扣减，失败只返还一次；取消成功订单不重置购票凭据。

Redis key 使用 `ticketing:seckill:v2:<数据库命名空间>:`。未完成申请不设置 TTL；终态详情保留七天，较小的幂等摘要长期保留以继续复用 ID 和终态，详情到期后仍可从 MySQL 查询。历史 MySQL 终态在票档首次受控初始化时预装到 Redis，不通过 HTTP 同步查询历史申请。幂等摘要需要纳入 Redis 容量管理，不能未经协调随意删除。

## 唯一购票恢复入口

`PurchaseRecoveryJob` 小批量读取待恢复 ZSET，成员为 skuId:requestId，分数为下次检查时间。重复扫描允许重复投递，不增加恢复认领租约、分布式锁或线程池。

- 有 MySQL SUCCEEDED：补齐 CONSUMED；有 FAILED/EXPIRED：补齐 RELEASED。
- 无 MySQL 终态且仍有效：按原 requestId 直接重新发布 RabbitMQ。
- 已过期：先在 MySQL 以相同唯一键/行锁仲裁，再结算 Redis。迟到消费者必须读取原终态，不能同时成功建单和过期返票。
- Redis 凭据、库存账本或容量计数丢失：暂停对应 SKU，保留明确异常并等待核对；不从 MySQL 剩余库存猜测重建未完成预扣。

Redis 继续使用 AOF、`appendfsync always` 和 `noeviction`。Redis、RabbitMQ、MySQL 之间没有强分布式事务，不承诺绝对零丢失；Redis 持久化数据永久丢失、外部误删、长时间基础设施故障可能需要人工核对。死信仅保存失败投递，不作为自动返票依据。

## 库存同步与候补

`StockSnapshotService` 收敛为父资源→SKU 行锁内的小型维护，不再保存 owner/fence/mutation、多层冻结租约、孤儿恢复或自动重建框架。`ticket_sku.redis_inventory_initialized` 区分从未初始化和已初始化后 Redis 账本丢失；后者停止受理，不能静默重置。

首次初始化或库存事件到来时，先暂停 SKU 新受理。有未完成普通预扣就等待结算；归零后，在 SKU 行锁内读取 MySQL 已提交的总量、locked、sold、销售资格，发布绝对可售余额与最新库存事件序号。消费者可以继续处理已经受理的凭据，避免暂停阻塞自身恢复。Redis 余额同步不使用数量累加，重复释放事件只会再次读取同一数据库事实，不会重复加票。

候补现在只负责持久 FIFO 与优先级调度，不创建订单、不另扣 MySQL 库存。旧 `allocateHead()`、`reserveWaitlist()`、`pauseForWaitlist()` 路径已删除。`PurchaseAdmission.submit(PurchaseIntent)` 由普通 HTTP 与可信候补调度共同调用，来源贯通 Redis、申请终态与正式订单；HTTP DTO 不能指定 WAITLIST_CONFIRM。

同 SKU 入队、挑选队首、取消/过期与清空门禁都使用原账号→父资源→SKU 锁序。入队持 SKU 锁，在 Lua 中原子检查真实 Redis 可预扣余额并先开启 `waitlistPriority`，之后提交 WAITING；回滚只可能留下保守门禁，由同一 SKU 恢复扫描确认空队列后关闭。普通 Lua 先处理幂等重放，再检查门禁，开启前已预扣请求继续有效。关门禁也必须在 SKU 锁内重查没有 WAITING/PROCESSING，且 Redis waitlistPending 为零。队首重查禁用 MyBatis 本地查询缓存，不能把加锁前的空结果用于加锁后的判断。

`WaitlistDispatcher` 在短事务中以最小 ID 选择 WAITING/PROCESSING；合格 WAITING 变 PROCESSING，绑定持久 attemptNo、requestId 和 `waitlist-{id}-{attemptNo}` 键。事务外才调用统一入口并等待同一个 MQ 发布确认。可信候补预扣也使用同一份 purchase-reserve.lua，校验 SKU 上所选队首/尝试，不绕过销售窗口、容量、限额或 MySQL 条件预占。库存不足不跳队，PROCESSING 未决时不能分配下一位，前端显示“分配中”且不可直接取消。

同一消费者取得请求协调锁和原交易资源锁，`WaitlistPurchaseLifecycle` 在 SKU 锁后再次确认队首；统一 `OrderReservation.reservePurchase()` 完成建单后，在同一事务写 SUCCEEDED/orderId 及 PROCESSING→ALLOCATED。临时 SQL 故障全部回滚并重试；确定失效在最终结果仲裁内变 EXPIRED。库存不足/排队超时等可重试失败保持 PROCESSING，只有 SQL 终态与 Redis 结算完成后才恢复 WAITING、递增 attemptNo。没有预扣的明确无票拒绝也先在同一 Lua 中使旧尝试失效，防止迟到并发提交与尝试推进交叉；回滚由原 PROCESSING 事实重新发布所选队首。

ALLOCATED 支付后 COMPLETED，取消/订单超时后 EXPIRED，退款保留已完成历史。历史分配无 purchase_request_id 时继续验证原 `waitlist-{id}` 键；新尝试一律使用代数键。Redis waitlistPending 在购票结算完成前阻止下一队首预扣或清空门禁，避免 SQL 已终结而 Redis 尚未结算时越队。

`WaitlistCompensation` 仅有一项有界 SKU 游标扫描，覆盖 WAITING、PROCESSING 及空队列残留门禁；扫描全部 SKU 是为了恢复“Redis 已开门禁但入队事务回滚、数据库没有队列记录”的情况。消息丢失、未知发布和 SQL/Redis 结算恢复仍由唯一 PurchaseRecoveryJob 处理。没有新增候补建单队列、消费者、Outbox 或定时器。

## 通用库存 Outbox

Outbox 只服务仍在使用它的库存释放、库存/销售配置变更和候补事件；普通购票不写、不读购票 Outbox。删除 PurchaseCommands、购票发布分支、recover/close/failed SQL 及废弃命令类型；保留库存事件载荷 eventId/skuId/reason、现有交换机/队列和独立事件 ID。

`InventoryEvents` 与取消、超时、退款或管理更新在同一个事务写事件。`OutboxService` 在短事务中 SKIP LOCKED 认领，递增投递令牌和持久租约防迟到回调覆盖；发布器事务外使用有界窗口、Confirm、mandatory，失败退避与死信保持。`InventoryConsumer` 验证数据库已提交事件后调用候补/库存同步。既有 `WaitlistCompensation` 按 SKU 有界补偿队首、未决尝试和门禁，不能将 SENT 视为库存同步完成。

## 原业务边界

交易锁序继续为账号 SHARE→活动 SHARE→场次 SHARE→需要写额度时的用户场次额度 UPDATE→SKU UPDATE→已有订单→候补→电子票。消费的请求唯一键协调先于这条资源锁链；候补失效仲裁在请求锁后按相同资源顺序锁账号、父资源和 SKU，再更新候补。管理员/多账号操作及退款保持原升序账号协议，核销不占买家额度。

MySQL 是订单和最终库存权威。默认每用户每场四张，PENDING/PAID 占额，取消/到期/退款条件释放，支付不重复占额。金额由服务端人民币分整数乘算并保存快照。订单截止、状态条件更新、整单模拟退款、场次取消善后、原 paid_at 和核销历史保持。

`OrderLifecycle` 在同一 MySQL 事务先候补再出票，不改为提交后异步联动。电子票签名、摘要存储、密钥轮换、工作人员角色和场次授权、最多一次核销、CSRF、Session/BCrypt 均保持。候补与核销只访问交易公开契约；架构检查继续生效。

公开目录仍用 Caffeine/Redis，交易与授权查询绕过展示缓存，实时票档库存/销售状态/serverTime 仍由 MySQL 返回。客户端查询 Redis QUEUED 或 MySQL 最终结果，显示“已受理”后轮询真实订单。lookup、详情和本人活动申请列表保留原路径和所有权校验；数据库暂时没有申请行时不会误报不存在。普通购票只有 QUEUED/终态；候补新增 PROCESSING“分配中”展示，保留原键恢复、支付、取消、候补、票券和核销流程。

## 配置和迁移

普通启动方式、绝对路径、API 路径及单模块结构保持。`PURCHASE_IN_FLIGHT_WINDOW` 按用户2026年10月28日最新要求默认64，可配置1–256；`PURCHASE_CONFIRM_TIMEOUT_MS` 默认3000ms，业务容量默认200、消费者默认2。本轮隔离验证使用100线程/100在途/容量200/发布窗口64/12消费者、8000登录用户，12消费者只作为隔离启动参数。命名空间默认取MYSQL_DATABASE。保留容量、排队截止、消费者并发/预取、安全限流、库存Outbox配置，删除旧购票处理租约和无调用的购票双桶限速器配置。

V1–V13 不变；V14 在任何破坏性 DDL 前拒绝仍有 QUEUED/PROCESSING 的旧库，要求先停写、停止旧发布/消费者并排空旧链路。迁移保留所有订单、支付、候补和历史申请终态，删除废弃 purchase_capacity、购票 Outbox 记录及申请处理租约/token 等字段，增加首次库存初始化标记。已有业务库升级仍须具体授权，不自动 repair、清库或降级。

V15 扩展 purchase_request 的 WAITLIST_CONFIRM 来源与 waitlist_entry_id，候补增加 attempt_no、purchase_request_id 和 PROCESSING 活跃唯一约束。没有新增请求表；不在请求插入时增加候补外键行锁，候补关联由 SKU 协调、事务参与者和审计验证，避免倒置订单→候补锁序。V1–V14 保持不变。

用户于 2026-10-27 明确授权更新本机开发库。已停止旧后端，复核无 QUEUED/PROCESSING 申请、无未完成购票消息，退款/候补/额度预检通过后，将 127.0.0.1:3307/ticketing 从 V13 升级至 V15，两项迁移登记成功，未新增备份。新版后端8080与前端5173返回正常；升级后库存、订单、支付、候补、电子票、额度及申请审计全部零偏差，7个Redis账本与MySQL一致。原账号/活动/订单/候补行数保留3/8/6/1，没有在业务库注入测试。仅本机开发库授权，不包含生产部署或以后迁移。

## 验证范围

使用现有 `scripts/verification/verify_backend.py`，只连接独立 13307 MySQL、16379 Redis、15673 RabbitMQ 和随机 schema，不加载业务 .env。`--tests` 支持 Failsafe 类与方法筛选（#、+），避免无关全量运行。

本轮最终运行18个集成用例全部通过：`WaitlistManagementIT`七个候补场景，`AsyncPurchaseIT`八个统一购票场景，以及场次额度/候补退款、整单退款、核销各一个既有回归。覆盖候补优先和旧普通预扣、三人FIFO队首不足两张、统一MQ建单、重复库存事件、PROCESSING与未知发布、SQL回滚/确定失败/结算恢复及尝试号、并发入队与清空门禁、支付出票和订单超时。测试仅使用独立端口13307/16379/15673、随机schema和guard，V15新迁移在独立库先验证。

Maven自带98个普通测试全部通过，包括架构、配置、库存Outbox、安全与凭证；前端严格类型检查及生产构建通过。新版后端启动时再次完成编译，迁移后执行本机只读健康与数据审计。没有新增测试框架，没有性能测试或浏览器端到端回归，不从功能验证推导RPS/P99。独立验证容器、数据卷和私有临时配置在交付前清理，业务容器与卷保留。
