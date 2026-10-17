# 数据库 V1 至 V3

迁移文件：`backend/src/main/resources/db/migration/V1__ticketing_schema.sql`。使用 MySQL 8.4（最低需正确执行 CHECK 的 MySQL 8.0.16+）、InnoDB、utf8mb4、DATETIME(6)。Flyway 校验已应用迁移，后续修改新增 V2/V3，不能覆盖 V1。无业务种子数据。

V2 新增 `V2__activity_session_management.sql`，V1 保持不变。V2 复用 description 作为详情，增加 summary、cover_url、published_at、version；活动状态为 DRAFT/PUBLISHED/OFFLINE，兼容将旧 CANCELLED 转为 OFFLINE。增加 `(status,published_at,id)` 支持公开列表排序。场次增加 sale_starts_at、version 及开售早于开始的 CHECK；旧数据按开始前30天补齐开售时间，新建必须输入。更新时间与版本由业务操作显式更新。

当前为 V3，`V3__ticket_sku_version.sql` 只在既有 ticket_sku 增加 version 及非负 CHECK，原有外键、同场次名称唯一约束、库存 CHECK 和索引保留，V1/V2 未修改。票档名称在场次内不区分大小写唯一，写入前去除首尾空格。管理版本从0开始，成功修改递增。

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
| ticket_order | 用户与场次外键；订单号唯一；幂等域唯一 | 用户+创建时间+ID；状态+过期时间+ID |
| ticket_order_item | 订单唯一，第一版每单一个票档；复合外键确保票档与订单同场次 | 订单定位、SKU 关联 |
| payment_record | 订单唯一、流水号唯一；一单一个可重试模拟支付流水 | 按订单查支付 |
| waitlist_entry | 用户、SKU、预留订单外键；同用户同 SKU 仅一条有效候补 | SKU+状态+ID FIFO；用户记录；过期扫描 |
| electronic_ticket | 票号、凭证摘要、明细内序号唯一；复合外键确保用户和明细来源一致 | 用户票列表、来源订单、核销人员 |
| outbox_event | 事件 ID 唯一；JSON 载荷；状态与重试字段 | 状态+下次重试时间+ID；聚合追踪 |

外键默认限制删除，不使用级联删除交易事实。复合被引用列具有完整唯一索引，兼容 MySQL 8.4 对非唯一引用键的限制。活动发现索引首先服务按城市和分类过滤；若后续全站列表成为热点，用实际查询计划决定新增索引，当前不为所有字段建立单列索引。

## 库存与交易一致性

数据库强制 `total_stock >= 0`、`locked_stock >= 0`、`sold_stock >= 0`、`locked_stock + sold_stock <= total_stock`。可售量为 `total_stock - locked_stock - sold_stock`，不得独立维护另一份可售量。

后续下单在一个 MySQL 事务内按开售窗口、票档状态、数量和可售量条件更新锁定库存，再插入订单、唯一明细及 Outbox，任何一步失败则整体回滚。支付将订单 `PENDING → PAID` 与库存 `locked → sold` 同事务提交；取消/过期按 `PENDING` 状态竞争释放，只有更新成功的一方释放库存。当前已验证票档管理的库存约束和并发编辑冲突，尚未实现交易业务流程或购票并发算法。

单价、总额、支付金额的相等关系，以及电子票数量不超过明细数量、候补预留订单与 SKU 一致，属于跨行业务不变量。CHECK 不能跨表验证，必须在后续事务代码中落实；外键和库存 CHECK 不能代替完整业务一致性。

跨票档的用户/场次限购不能依靠并发 `SELECT COUNT(*)`。下一阶段选择事务内原子配额维护（新增迁移建立用户+场次唯一配额并条件增减），或按用户锁行串行处理所有占额、释放及支付路径；只有所有相关写入都遵循同一锁协议后，锁内统计才有效。当前未创建备用配额表。

## 幂等、候补与事件

订单唯一键为 `(user_id, idempotency_operation, idempotency_key)`。`CREATE_ORDER` 与 `WAITLIST_CONFIRM` 是不同操作域；服务端决定域，客户端不能自行改域。幂等键采用区分大小写 ASCII，保留到订单事实保留期结束。`request_hash` 保存规范化 SKU、数量等参数的 SHA-256，同键同参返回原逻辑结果，同键异参返回 409。落库唯一约束兜底并发重复提交。

候补使用自增 ID 作为同票档内部顺序；调度器未来按 `(sku_id, status, id)` 排队处理。事务提交次序可能不同于 ID 分配次序，严格 FIFO 若要覆盖尚未提交的加入操作，必须使用同 SKU 队列串行协议。数量不足时是否阻塞队首须在业务阶段明确；本计划默认不跳过队首。当前不承诺候补优先于新订单，若要严格优先，需让新订单与候补分配共享同 SKU 调度协议。

`active_marker` 在 WAITING/ALLOCATED 时为 1，终态为 NULL；唯一键限制有效重复候补，同时保留历史。Outbox 与交易同事务写入；代理确认后标记 SENT，PROCESSING 使用 `next_retry_at` 作为租约截止，超时恢复。消费端需通过状态条件更新或后续新增消费去重表实现幂等，不能把发出消息当作业务成功。

## 电子票安全方案（待实现）

库中只保存完整票券凭证的 SHA-256 摘要、不可预测的公开票号和签名密钥版本，不存可重用票码明文。计划用版本化服务端密钥 HMAC 签名不可变的 `ticket_no + key_version`，形成可重建凭证；重新展示时先认证并核对持票用户，再重建凭证并比对已存摘要。密钥保存在服务端密钥管理中，不与数据库一起存储；轮换保留有效旧版本直到票失效。此方案避免“只有不可逆摘要却承诺能还原原票码”的矛盾。

核销端验证签名、摘要、场次和权限，再用 `status='UNUSED'` 条件更新为 USED，同事务记录核销时间和人员；受影响行为 1 才算首次成功。凭证在使用前可被持有者复制，后续如需动态二维码，另行设计时效与重放策略。本阶段没有生成凭证或核销 API。

## 金额、时间与 ID

人民币金额一律 BIGINT 分，不使用浮点运算。票档 API 以十进制整数字符串传递金额分，前端用 BigInt 精确转换展示为 `¥299.00`。数据库 BIGINT ID 也以字符串传输，禁止用 JS number 表达任意长 ID。

所有 DATETIME(6) 为 UTC；Compose MySQL 默认时区与 JDBC 会话均为 `+00:00`。后续 Java 使用 Instant 或明确 UTC 转换的类型，API 使用带 `Z`/偏移的 ISO 8601；中国用户通过 `Intl.DateTimeFormat('zh-CN', {timeZone:'Asia/Shanghai'})` 展示北京时间。不要把 DATETIME 的无时区文本直接给前端猜测。
