# 数据库 V1 至 V4

迁移文件位于 `backend/src/main/resources/db/migration/`。使用 MySQL 8.4（最低需正确执行 CHECK 的 MySQL 8.0.16+）、InnoDB、utf8mb4、DATETIME(6)。Flyway校验已应用迁移，V1至V4禁止覆盖；后续结构变化新增V5及更高版本。无业务种子数据。

V2 新增 `V2__activity_session_management.sql`，V1 保持不变。V2 复用 description 作为详情，增加 summary、cover_url、published_at、version；活动状态为 DRAFT/PUBLISHED/OFFLINE，兼容将旧 CANCELLED 转为 OFFLINE。增加 `(status,published_at,id)` 支持公开列表排序。场次增加 sale_starts_at、version 及开售早于开始的 CHECK；旧数据按开始前30天补齐开售时间，新建必须输入。更新时间与版本由业务操作显式更新。

`V3__ticket_sku_version.sql`只在既有ticket_sku增加version及非负CHECK，原有外键、同场次名称唯一约束、库存CHECK和索引保留。票档名称在场次内不区分大小写唯一，写入前去除首尾空格。管理版本从0开始，成功修改、订单预占和释放均递增。

第三阶段认证完整复用user_account现有字段，无新增迁移。用户名采用3至32位ASCII字母、数字、下划线且字母开头；唯一键与utf8mb4_0900_ai_ci负责大小写不敏感唯一性。注册仅创建USER/ACTIVE，password_hash保存Spring Security BCrypt编码，不保存明文。密码8至72个字符、UTF-8不超过72字节且无控制字符；数据库管理员初始化额外要求至少16个字符。角色与状态保留既有CHECK。Session及CSRF保存在单实例内存，不创建会话表、不引入Redis认证。

第四阶段V4为 `V4__order_user_session_index.sql`，仅新增ticket_order索引 `idx_order_user_session_status(user_id,session_id,status,id)`，支持用户场次有效数量统计；复用V1的订单、明细、幂等唯一键及库存约束，不修改V1至V3。选择用户行锁保护全部占额和释放路径，不增加独立配额表及重复计数字段。

第五阶段直接复用ticket_order、ticket_order_item、payment_record、ticket_sku及 `idx_order_expiry(status,expires_at,id)`。已有字段和约束足够，无V5迁移，V1至V4均未修改。

第六阶段复用waitlist_entry、outbox_event及上述交易表，仍不新增迁移、不进行表结构重构。有效候补唯一键uk_waitlist_active、FIFO索引idx_waitlist_queue、本人查询索引idx_waitlist_user和Outbox认领索引idx_outbox_dispatch均继续使用。

票档价格为 BIGINT 整数分，API 以十进制字符串传输（0至9223372036854775807），前端通过 BigInt 换算人民币元，禁止浮点截断。库存与限购为 INT 整数，接口拒绝小数。只允许管理员修改 total_stock，locked_stock/sold_stock 为只读，可售量即时计算，不落库。

销售窗口必须满足 `场次开售 <= 票档开售 < 票档停售 <= 场次开始`。票档变更及场次修改共享场次行锁，修改场次前检查全部已有票档窗口；跨表规则由事务代码维护，不能用单表 CHECK 代替。旧数据窗口不一致时统一返回暂不可售。已启用销售仅可调整总库存，其他字段先下架再改；总库存条件更新仍须大于等于锁定与已售之和。

每场次最多200票档，列表按价格分、ID升序。公开查询仅返回 ON_SALE，DRAFT/OFF_SALE 不公开；活动未发布404，活动与场次归属不一致404。管理状态与实时销售状态分离，后者由服务端结合父级状态、UTC时间和库存计算，详见 API 契约。

开发样例位于 `scripts/demo-data.sql` 和 `scripts/demo-tickets.sql`，不在迁移目录，仅人工导入，所有名称标明演示。SQL 依靠名称和场次判断避免普通重复导入，不保证并行导入幂等，不在生产环境执行。

| 表 | 关系及关键约束 | 主要索引用途 |
| --- | --- | --- |
| user_account | 用户名唯一；角色 USER/ADMIN；账号 ACTIVE/DISABLED | 用户名登录定位 |
| activity | 活动分类、城市、场馆、发布状态 | 状态+城市+类别+ID 活动发现 |
| activity_session | 活动外键；结束时间晚于开始时间 | 活动+开始时间+ID 场次列表 |
| ticket_sku | 场次外键；同场次票档名唯一；库存、金额、窗口 CHECK | 场次列表；ID+场次复合引用 |
| ticket_order | 用户与场次外键；订单号唯一；幂等域唯一 | 用户+创建时间+ID；状态+过期时间+ID；V4用户+场次+状态+ID |
| ticket_order_item | 订单唯一，第一版每单一个票档；复合外键确保票档与订单同场次 | 订单定位、SKU 关联 |
| payment_record | 订单唯一、流水号唯一；一单一个可重试模拟支付流水 | 按订单查支付 |
| waitlist_entry | 用户、SKU、预留订单外键；同用户同 SKU 仅一条有效候补 | SKU+状态+ID FIFO；用户记录；过期扫描 |
| electronic_ticket | 票号、凭证摘要、明细内序号唯一；复合外键确保用户和明细来源一致 | 用户票列表、来源订单、核销人员 |
| outbox_event | 事件 ID 唯一；JSON 载荷；状态与重试字段 | 状态+下次重试时间+ID；聚合追踪 |

外键默认限制删除，不使用级联删除交易事实。复合被引用列具有完整唯一索引，兼容 MySQL 8.4 对非唯一引用键的限制。活动发现索引首先服务按城市和分类过滤；若后续全站列表成为热点，用实际查询计划决定新增索引，当前不为所有字段建立单列索引。

## 库存与交易一致性

数据库强制 `total_stock >= 0`、`locked_stock >= 0`、`sold_stock >= 0`、`locked_stock + sold_stock <= total_stock`。可售量为 `total_stock - locked_stock - sold_stock`，不得独立维护另一份可售量。

下单在READ_COMMITTED事务内依次锁用户、活动、场次、SKU，校验账号ACTIVE、父级状态、统一销售窗口和两层限购。库存通过 `UPDATE ticket_sku SET locked_stock=locked_stock+quantity,version=version+1 ... WHERE status='ON_SALE' AND sale_starts_at<=UTC_TIMESTAMP(6) AND sale_ends_at>UTC_TIMESTAMP(6) AND total_stock-locked_stock-sold_stock>=quantity` 条件更新，活动和场次状态在父级锁内核验并稳定至提交。随后插入PENDING订单和唯一明细，任何写入失败全部回滚。订单总额通过Math.multiplyExact计算，明细保存票档名、单价分和数量快照。库存释放与管理员增库存从第六阶段起同事务写Outbox。

统一锁序为user_account UPDATE→activity SHARE→activity_session SHARE→ticket_sku UPDATE→已有ticket_order UPDATE。活动变更只锁活动；管理场次/票档先活动SHARE、再场次UPDATE、再SKU更新，防止下架、取消或改窗口与下单交叉；初次不加锁查询只发现不可变的父级ID，锁定后读取最新状态。管理员库存更新保留version及 `total_stock>=locked_stock+sold_stock` 条件；与预占竞争后可能409，不能覆盖已有锁定量。

主动取消同样先锁用户及父级/SKU再锁订单，仅PENDING允许转CANCELLED，条件释放locked_stock且检查受影响行数。重复取消CANCELLED直接返回原结果，其他状态409；释放与状态更新同事务回滚。活动下架或场次取消不立即处理旧订单，但不阻止用户主动取消或截止后自动回收。

模拟支付在相同锁序下先核对本人和PENDING状态及活动PUBLISHED/场次SCHEDULED，再执行 `UPDATE ticket_order SET status='PAID',paid_at=UTC_TIMESTAMP(6) ... WHERE status='PENDING' AND expires_at>UTC_TIMESTAMP(6)`。数据库在等锁后作实际截止判断；成功再条件更新SKU locked减quantity、sold加quantity，并插入SUCCEEDED支付流水。支付金额取订单总额快照，支付时间与订单paid_at一致；三个写入任何失败均回滚。支付不重新消耗可售库存，也不受SKU当前停售窗口影响，已经PAID的重试直接返回原流水。支付/取消/过期共同争夺同一用户和订单行，只能从PENDING到一个终态。

payment_record复用一单唯一约束uk_payment_order和流水唯一约束uk_payment_no，不创建重复的支付状态表。当前只落成功模拟支付记录；失败事务不保留半成品流水。订单与流水金额/时间相等由同一事务落实，库存CHECK不能单独证明此关系。

单价、总额、支付金额的相等关系，以及电子票数量等于已付明细数量、候补预留订单与 SKU 一致，属于跨行业务不变量。CHECK 不能跨表验证，必须在业务事务代码中落实；外键和库存 CHECK 不能代替完整业务一致性。

场次限额集中配置 `ticketing.order.max-per-user-session`，默认4。创建、支付、取消、过期先对该user_account执行FOR UPDATE；锁内以SUM明细quantity统计同用户同场次PENDING/PAID，跨票档共享额度。READ_COMMITTED确保等到前一事务提交后统计最新数量；统计加新数量不超过配置才允许预占。取消/过期转终态后自然不再计入，支付仍占额，交易失败不改变额度。单用户不同场次也串行，第一版接受此取舍，不使用无锁SUM或另一份配额计数。

支付期限集中配置，默认15分钟，创建时间与截止起点取MySQL UTC时间。截止后到扫描完成前PENDING仍占额、占库存，paymentDeadlinePassed=true且禁止支付；真正转CANCELLED/EXPIRED后才不计入。默认启动立即扫描，此后每批完成间隔30秒、每批100（1至500可配），使用既有到期索引按expires_at/id有界读取，游标到尾回绕；失败也前进，避免一笔持续失败阻塞其他订单，下次循环重试。内存游标仅用于公平扫描，不存业务事实，重启从数据库重新开始。

每笔过期订单独立事务，系统只对用户加锁、不校验ACTIVE，父级不可售也不阻止释放。拿齐锁后按 `status='PENDING' AND expires_at<=UTC_TIMESTAMP(6)` 条件转EXPIRED并释放locked，失败整体回滚为PENDING；其他状态及尚未截止返回无操作。扫描查询本身不加订单锁，多个实例/任务重复扫描仍由事务条件保证只释放一次。候补等占额路径仍须共享用户行锁，不允许绕过。

## 幂等、候补与事件

订单唯一键为 `(user_id, idempotency_operation, idempotency_key)`。`CREATE_ORDER` 与 `WAITLIST_CONFIRM` 是不同操作域；服务端决定域，客户端不能自行改域。幂等键采用区分大小写 ASCII，保留到订单事实保留期结束。`request_hash` 保存规范化 SKU、数量等参数的 SHA-256，同键同参返回原逻辑结果，同键异参返回 409。落库唯一约束兜底并发重复提交。

当前创建域固定CREATE_ORDER，锁用户后首先查询旧键，哈希输入为规范化十进制 `skuId:quantity`。同用户同键并发被用户锁串行化，后到者读取已提交原单，不重复占库存/限额；不同用户可使用相同键。已取消原单仍返回CANCELLED，不重新下单。若插入时发生唯一键等异常，异常穿透事务回滚，统一错误响应，禁止在已回滚事务中捕获后返回成功；失败后同键可正常重试。

候补使用自增ID作为同票档内部顺序，调度器按 `(sku_id,status,id)` 读取WAITING队首。入队先锁同SKU再生成ID，锁持有至提交，避免先拿较小ID却晚提交造成FIFO反转。分配先非锁定查询候选用户，再按用户UPDATE→活动SHARE→场次SHARE→SKU UPDATE顺序获取当前状态并重新查询队首；旧候选失效即释放全部锁再选，不锁着SKU等待下一用户。队首库存不足就停止，不跳过。队首过期、账号停用、父级/窗口失效或限额不足时条件转EXPIRED再处理下一位。FIFO仅限同SKU候补内部，不承诺优先于普通新购票。

`active_marker`在WAITING/ALLOCATED时为1，终态NULL；唯一键限制有效重复候补，同时保留历史。WAITING不占库存或场次限额，入队及分配均校验真实限额。分配调用OrderService共用预占方法，固定WAITLIST_CONFIRM域、waitlist-{候补ID}键及SKU/数量哈希；创建订单、明细、预占及候补转ALLOCATED同事务，先订单后候补。reserved_order_id唯一且外键核对用户；代码另行核对场次、SKU及数量，查询JOIN也校验，不以单表CHECK代替。支付转COMPLETED，取消/超时转EXPIRED，均在订单事务末端检查关联并更新，失配全部回滚。等待截止不晚于票档停售，已分配订单使用独立支付截止。

Outbox与库存释放/管理员增库存同事务；候补入队/退出也写调度提示，不在业务事务内发MQ。事件载荷只含eventId、skuId及reason。发布器短事务FOR UPDATE SKIP LOCKED取到期PENDING或租约超时PROCESSING并递增retry_count，next_retry_at记录租约；事务提交后发持久消息，代理确认且无mandatory退回才条件标SENT。SENT/重试更新须匹配PROCESSING和本次retry_count，旧发布器不能覆盖新认领者。确认丢失或崩溃允许重复消息，超过配置总次数转FAILED保留错误摘要。

消费端验证消息与已提交Outbox一致，以同SKU队首、有效状态条件和唯一订单键保证幂等，不新增消费去重表。部分分配成功后再失败，重投只处理剩余WAITING。持久队列/交换机、有界消费退避和死信保护消息链路；独立数据库扫描补偿WAITING队列，不以消息SENT宣称分配成功。至少一次投递不等于端到端Exactly Once。内存扫描游标只控制公平分页，任务重启仍按库中事实恢复。

## 电子票签发、补票与核销

第七阶段完全复用electronic_ticket，无V5，V1至V4不变。付款事务末端签发quantity张UNUSED票券，order_id/order_item_id/sku_id/owner_id来自数据库，sequence_no为1至quantity。票号为32字节SecureRandom经Base64URL无填充编码，唯一键uk_ticket_no、uk_ticket_digest和uk_ticket_item_sequence兜底。任何票券写入失败回滚订单PAID、库存转售出、流水及候补完成，不能部分出票。

历史补票默认关闭，显式配置后逐批扫描PAID订单，每单独立READ_COMMITTED事务；用户UPDATE→活动SHARE→场次SHARE→SKU UPDATE→订单UPDATE→票券UPDATE。已有票必须匹配明细、用户、SKU，序号在合法范围且摘要可重建；异常则保持所有现有票不变并报错，后续订单继续。缺失序号补齐，已有USED/VOID票不改状态、不删除；唯一序号加订单锁保证重复/并发补票幂等。扫描每批默认100（最大500），游标仅用于公平分页，回绕及重启重新查数据库。

完整凭证格式为 `TKT1.{keyVersion}.{ticketNo}.{signature}`，签名为HMAC-SHA256对前3段ASCII文本计算后的Base64URL无填充编码，完整凭证SHA-256保存在credential_digest。密钥由环境变量映射正整数版本至Base64密钥（32至128字节），不落库、不默认提供固定值；缺失或不合法拒绝启动。核对签名和摘要均使用MessageDigest.isEqual；拒绝非规范Base64URL编码、未知版本和篡改。重建凭证仍须检查数据库摘要，只有签名正确不足以核销。

核销先锁真实工作人员用户行并复查ADMIN，随后沿统一父级锁序锁SKU及订单，最后条件更新电子票。SQL要求UNUSED、关联订单PAID、场次SCHEDULED和MySQL UTC时间处于 `[starts_at-配置分钟,ends_at)`，默认提前60分钟。成功转USED，同语句记录used_at和checked_by，受影响行为1才算首次成功；重复扫描409且不改原记录。VOID拒绝入场，本阶段无公开作废或删除接口。核销不改库存、订单或支付。活动下架本身不自动作废已付票，场次取消/结束则拒绝核销。数据库CHECK继续强制USED必须同时有时间和人员。

## 金额、时间与 ID

人民币金额一律 BIGINT 分，不使用浮点运算。票档 API 以十进制整数字符串传递金额分，前端用 BigInt 精确转换展示为 `¥299.00`。数据库 BIGINT ID 也以字符串传输，禁止用 JS number 表达任意长 ID。

所有 DATETIME(6) 为 UTC；Compose MySQL 默认时区与 JDBC 会话均为 `+00:00`。后续 Java 使用 Instant 或明确 UTC 转换的类型，API 使用带 `Z`/偏移的 ISO 8601；中国用户通过 `Intl.DateTimeFormat('zh-CN', {timeZone:'Asia/Shanghai'})` 展示北京时间。不要把 DATETIME 的无时区文本直接给前端猜测。
