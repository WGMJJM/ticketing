# 数据库 V1 至 V4

迁移文件位于 `backend/src/main/resources/db/migration/`。使用 MySQL 8.4（最低需正确执行 CHECK 的 MySQL 8.0.16+）、InnoDB、utf8mb4、DATETIME(6)。Flyway校验已应用迁移，V1至V4禁止覆盖；后续结构变化新增V5及更高版本。无业务种子数据。

V2 新增 `V2__activity_session_management.sql`，V1 保持不变。V2 复用 description 作为详情，增加 summary、cover_url、published_at、version；活动状态为 DRAFT/PUBLISHED/OFFLINE，兼容将旧 CANCELLED 转为 OFFLINE。增加 `(status,published_at,id)` 支持公开列表排序。场次增加 sale_starts_at、version 及开售早于开始的 CHECK；旧数据按开始前30天补齐开售时间，新建必须输入。更新时间与版本由业务操作显式更新。

`V3__ticket_sku_version.sql`只在既有ticket_sku增加version及非负CHECK，原有外键、同场次名称唯一约束、库存CHECK和索引保留。票档名称在场次内不区分大小写唯一，写入前去除首尾空格。管理版本从0开始，成功修改、订单预占和释放均递增。

第三阶段认证完整复用user_account现有字段，无新增迁移。用户名采用3至32位ASCII字母、数字、下划线且字母开头；唯一键与utf8mb4_0900_ai_ci负责大小写不敏感唯一性。注册仅创建USER/ACTIVE，password_hash保存Spring Security BCrypt编码，不保存明文。密码8至72个字符、UTF-8不超过72字节且无控制字符；数据库管理员初始化额外要求至少16个字符。角色与状态保留既有CHECK。Session及CSRF保存在单实例内存，不创建会话表、不引入Redis认证。

第四阶段V4为 `V4__order_user_session_index.sql`，仅新增ticket_order索引 `idx_order_user_session_status(user_id,session_id,status,id)`，支持用户场次有效数量统计；复用V1的订单、明细、幂等唯一键及库存约束，不修改V1至V3。选择用户行锁保护全部占额和释放路径，不增加独立配额表及重复计数字段。

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

下单在READ_COMMITTED事务内依次锁用户、活动、场次、SKU，校验账号ACTIVE、父级状态、统一销售窗口和两层限购。库存通过 `UPDATE ticket_sku SET locked_stock=locked_stock+quantity,version=version+1 ... WHERE status='ON_SALE' AND sale_starts_at<=UTC_TIMESTAMP(6) AND sale_ends_at>UTC_TIMESTAMP(6) AND total_stock-locked_stock-sold_stock>=quantity` 条件更新，活动和场次状态在父级锁内核验并稳定至提交。随后插入PENDING订单和唯一明细，任何写入失败全部回滚。订单总额通过Math.multiplyExact计算，明细保存票档名、单价分和数量快照。未写Outbox，本阶段无异步业务。

统一锁序为user_account UPDATE→activity SHARE→activity_session SHARE→ticket_sku UPDATE→已有ticket_order UPDATE。活动变更只锁活动；管理场次/票档先活动SHARE、再场次UPDATE、再SKU更新，防止下架、取消或改窗口与下单交叉；初次不加锁查询只发现不可变的父级ID，锁定后读取最新状态。管理员库存更新保留version及 `total_stock>=locked_stock+sold_stock` 条件；与预占竞争后可能409，不能覆盖已有锁定量。

主动取消同样先锁用户及父级/SKU再锁订单，仅PENDING允许转CANCELLED，条件释放locked_stock且检查受影响行数。重复取消CANCELLED直接返回原结果，其他状态409；释放与状态更新同事务回滚。活动下架或场次取消不自动处理旧订单，但不阻止用户主动取消。未来支付将PENDING→PAID与locked→sold同事务提交，超时关单与支付竞争仍须遵守上述用户锁及锁序。

单价、总额、支付金额的相等关系，以及电子票数量不超过明细数量、候补预留订单与 SKU 一致，属于跨行业务不变量。CHECK 不能跨表验证，必须在后续事务代码中落实；外键和库存 CHECK 不能代替完整业务一致性。

场次限额集中配置 `ticketing.order.max-per-user-session`，默认4。所有创建与取消先对该user_account执行FOR UPDATE；锁内以SUM明细quantity统计同用户同场次PENDING/PAID，跨票档共享额度。READ_COMMITTED确保等到前一事务提交后统计最新数量；统计加新数量不超过配置才允许预占。取消转终态后自然不再计入，交易失败不改变额度。单用户不同场次也串行，第一版接受此取舍，不使用无锁SUM或另一份配额计数。

支付期限集中配置，默认15分钟。超过截止的PENDING仍占额、占库存，服务端返回paymentDeadlinePassed；未实现自动EXPIRED或回收。真正CANCELLED/EXPIRED不计入额度。后续支付、超时、候补分配必须先锁同一用户行再占额/释放，后台释放不得因账号停用跳过清理。当前没有上述未来写入路径。

## 幂等、候补与事件

订单唯一键为 `(user_id, idempotency_operation, idempotency_key)`。`CREATE_ORDER` 与 `WAITLIST_CONFIRM` 是不同操作域；服务端决定域，客户端不能自行改域。幂等键采用区分大小写 ASCII，保留到订单事实保留期结束。`request_hash` 保存规范化 SKU、数量等参数的 SHA-256，同键同参返回原逻辑结果，同键异参返回 409。落库唯一约束兜底并发重复提交。

当前创建域固定CREATE_ORDER，锁用户后首先查询旧键，哈希输入为规范化十进制 `skuId:quantity`。同用户同键并发被用户锁串行化，后到者读取已提交原单，不重复占库存/限额；不同用户可使用相同键。已取消原单仍返回CANCELLED，不重新下单。若插入时发生唯一键等异常，异常穿透事务回滚，统一错误响应，禁止在已回滚事务中捕获后返回成功；失败后同键可正常重试。

候补使用自增 ID 作为同票档内部顺序；调度器未来按 `(sku_id, status, id)` 排队处理。事务提交次序可能不同于 ID 分配次序，严格 FIFO 若要覆盖尚未提交的加入操作，必须使用同 SKU 队列串行协议。数量不足时是否阻塞队首须在业务阶段明确；本计划默认不跳过队首。当前不承诺候补优先于新订单，若要严格优先，需让新订单与候补分配共享同 SKU 调度协议。

`active_marker` 在 WAITING/ALLOCATED 时为 1，终态为 NULL；唯一键限制有效重复候补，同时保留历史。Outbox 与交易同事务写入；代理确认后标记 SENT，PROCESSING 使用 `next_retry_at` 作为租约截止，超时恢复。消费端需通过状态条件更新或后续新增消费去重表实现幂等，不能把发出消息当作业务成功。

## 电子票安全方案（待实现）

库中只保存完整票券凭证的 SHA-256 摘要、不可预测的公开票号和签名密钥版本，不存可重用票码明文。计划用版本化服务端密钥 HMAC 签名不可变的 `ticket_no + key_version`，形成可重建凭证；重新展示时先认证并核对持票用户，再重建凭证并比对已存摘要。密钥保存在服务端密钥管理中，不与数据库一起存储；轮换保留有效旧版本直到票失效。此方案避免“只有不可逆摘要却承诺能还原原票码”的矛盾。

核销端验证签名、摘要、场次和权限，再用 `status='UNUSED'` 条件更新为 USED，同事务记录核销时间和人员；受影响行为 1 才算首次成功。凭证在使用前可被持有者复制，后续如需动态二维码，另行设计时效与重放策略。本阶段没有生成凭证或核销 API。

## 金额、时间与 ID

人民币金额一律 BIGINT 分，不使用浮点运算。票档 API 以十进制整数字符串传递金额分，前端用 BigInt 精确转换展示为 `¥299.00`。数据库 BIGINT ID 也以字符串传输，禁止用 JS number 表达任意长 ID。

所有 DATETIME(6) 为 UTC；Compose MySQL 默认时区与 JDBC 会话均为 `+00:00`。后续 Java 使用 Instant 或明确 UTC 转换的类型，API 使用带 `Z`/偏移的 ISO 8601；中国用户通过 `Intl.DateTimeFormat('zh-CN', {timeZone:'Asia/Shanghai'})` 展示北京时间。不要把 DATETIME 的无时区文本直接给前端猜测。
