# 票务交易平台开发规范

- 项目中文名称为「票务交易平台」，英文技术名为 `ticketing`，Java 根包统一为 `com.ticketing`。源码位于 `backend/src/main/java/com/ticketing`，测试位于 `backend/src/test/java/com/ticketing`，后续新增代码保持一致。
- Java 21，遵循《阿里巴巴Java开发手册》；使用明确命名与必要的中文注释，DTO 优先使用 record。
- 保持前后端分离、模块化单体架构，后端只有 `backend/pom.xml` 一个 Maven 模块。不得擅自引入微服务或额外基础设施。
- 第十四阶段按用户已确认的执行文档改为业务包组织，仍保留一个Maven模块和唯一Spring Boot应用。业务包为`activity`、`inventory`、`auth`、`admission`、`trade`、`waitlist`、`checkin`、`staff`、`audit`；共用响应归`common`，通用消息设施归`messaging`，装配配置归`appconfig`。保留既有职责，不创建空模块或第二个可启动服务。
- 不创建空接口、空 Store、多余工具类、无意义抽象、魔法值、硬编码凭据或虚假业务。不泛滥使用无依据的防御分支。
- Flyway 已应用的迁移不可修改；V1至V7保持不变，第十四阶段仅新增V8及更高版本。不得迁移旧 `rrs` 项目代码和数据。
- 第十阶段已授权整单模拟退款和场次取消善后，旧阶段“不开发退款”的边界不阻止本阶段实现；不接入真实支付或真实退款。V6增加REFUNDED、refund_record及order_aftercare，部署前只读执行scripts/audit-refund-readiness.sql和候补关联审计，异常只报告并停止，DDL失败保留现场，不自动repair或清库。
- 整单退款只允许PAID且电子票完整并全部UNUSED，保留paid_at及成功支付事实。退款在升序账号SHARE→活动SHARE→场次SHARE→用户场次额度UPDATE→SKU→订单→候补→电子票锁序内条件减sold和额度、作废未用票、写唯一订单退款流水及Outbox；复查操作人ACTIVE/ADMIN。REFUNDED不再占库存/限额，候补COMPLETED历史保持不变。已核销、缺票或关联异常记录MANUAL_REQUIRED，不改写核销历史。
- 场次取消只快速提交CANCELLED；善后按数据库事实有界逐单事务，PENDING关闭释放locked，PAID复用整单模拟退款。禁用买家也处理；失败退避至最多配置次数后人工处理，游标失败前进、回绕及重启补偿。活动OFFLINE不自动退款。善后与事务协作不可改为提交后异步原子更新。
- 账号统一使用数据库user_account与Spring Security Session，密码只存BCrypt。禁止恢复Basic、引入JWT双轨或在前端持久化凭据；写请求统一CSRF，登录和退出后重新获取令牌。后续业务从AccountPrincipal获取可信用户ID，不接受前端身份声明。管理员仅通过显式初始化创建，不允许公开注册提权。
- 需要身份的端点须在授权前按用户ID检查数据库最新状态和角色，禁用账号使会话失效，角色变化刷新会话身份。新增受保护端点必须加入SecurityConfig共用的RequestMatcher；公开查询不得额外查询账号。
- MySQL 是交易与库存权威；库存预占、支付转售出、取消与超时释放必须在事务中使用状态及库存条件更新。
- 第十六阶段交易使用READ_COMMITTED事务，账号状态协调改为user_account SHARE锁→活动SHARE锁→场次SHARE锁→需写额度时的用户场次额度UPDATE锁→票档UPDATE锁→已有订单UPDATE锁→候补→电子票。支付、核销及历史补票不写额度，可省略额度锁。管理场次/票档仍先操作人UPDATE、活动SHARE再场次UPDATE；权限变更及多账号操作按ID升序。禁止先锁子资源再补锁额度、账号或父资源。
- 用户场次限额沿用ORDER_SESSION_LIMIT，默认4张，user_session_quota精确记录同用户同场次全部票档的PENDING/PAID占额。先在父资源之后、票档之前用唯一键安全创建并锁额度行；预占与额度条件增加同事务，成功取消/过期/退款后条件减额，支付不再次占额，候补WAITING不占额。热路径和本人限额查询不再聚合历史订单，SUM仅供审计。释放任务不得因账号停用遗留库存。发现释放不足须回滚，不裁剪至零。
- CREATE_ORDER幂等域固定，用户锁内先查键与请求哈希；同参返回原订单（含已取消），异参409，唯一键为最终约束。禁止捕获事务内唯一键异常后继续返回成功。订单金额从服务端票价精确乘算，保存明细快照。
- 第五阶段仅模拟支付，不接入真实支付或实际扣款。支付按数据库截止条件将PENDING转PAID，同事务locked转sold并保存唯一订单流水；重复成功返回原流水。超时扫描按MySQL时间与到期索引有界读取，每单独立事务PENDING转EXPIRED并释放库存；清理只要求用户行锁，不做ACTIVE校验。扫描游标失败也前进，回绕重试，不阻塞其他订单。
- 截止后扫描前可能短暂仍为PENDING并占库存/额度，前端禁用支付且不伪装成已关闭，状态由后端返回。后台首次启动即扫描，重启可补偿；不得将内存游标当作可靠状态或引入MQ延迟作为唯一保障。
- 第六阶段候补入队与分配都持有同一SKU行锁，自增ID在锁内生成；第十六阶段分配先非锁定读队首用户，再按账号SHARE→活动→场次→该用户场次额度→SKU顺序加锁并重查队首。入队只读额度预检，不锁额度、不占额。库存不足不跳过队首，失效资格转EXPIRED。每笔分配独立事务，订单锁之后才更新候补行；禁止持有SKU锁再获取额度或下一用户锁。
- WAITING不占库存或场次额度，分配时重新校验；候补订单使用固定WAITLIST_CONFIRM域与waitlist-{id}键，复用订单预占和金额快照。分配、预占与ALLOCATED同事务，支付联动COMPLETED，取消/过期联动EXPIRED；均须核对用户、场次、SKU、数量和关联订单。
- 库存释放、管理员增加库存与Outbox同事务写入。发布器短事务SKIP LOCKED认领，租约与递增尝试令牌防旧任务覆盖，RabbitMQ持久消息经确认且无退回才标SENT；有界退避、死信及低频数据库补偿共同恢复，语义是至少一次投递与数据库幂等。FIFO仅限同SKU候补内部，不承诺优先于普通购票。
- 第七阶段支付成功同事务按明细数量签发电子票，只有PAID允许出票；先订单/候补再票券，不反向获取父级锁。按明细序号唯一约束防重复，检查已有票的数量、序号、关联及摘要，补缺不删票。历史补票默认关闭，显式启用后有界逐单事务，失败前进、回绕重试，复用用户→父级→SKU→订单锁序。
- 电子票完整凭证格式TKT1.密钥版本.随机票号.签名，票号为32字节安全随机数，签名HMAC-SHA256，数据库仅存完整凭证SHA-256摘要。签名/摘要常量时间比较。签名密钥仅从环境变量提供，至少32字节Base64密钥，启动时严格校验；轮换保留旧有效版本，禁止默认固定密钥。凭证不放URL、日志、Store或浏览器存储，凭证响应no-store，页面切换清理二维码。
- 核销复用ADMIN和CSRF，工作人员账号先取SHARE锁并复查角色，与撤权/停用的UPDATE锁协调，再按活动→场次→SKU→订单→票券处理，不锁持票人的额度。数据库最终校验PAID、SCHEDULED及演出前配置窗口至结束，UNUSED条件更新为USED并记录真实操作人/UTC时间。最多一次成功，USED/VOID不能再用。核销不改变订单、支付或库存，后续工程调整必须保持以上约束，不开发真实支付或退款。
- 场次开售时间是票档最早开售时间，票档停售不得晚于场次开始。票档管理和场次时间修改必须共享场次行锁并验证窗口；已启用销售的票档只可调整总库存。管理 API 禁止写入锁定、已售和可售库存，销售状态统一由后端计算。
- Redis 仅用于明确的缓存、限流或削峰职责。RabbitMQ 异步事件必须设计 Outbox、确认、重试和幂等消费。
- 第十五阶段公开活动/场次展示使用有界Caffeine与Redis缓存，提交后换代防止旧查询回填当前代；管理、授权和交易权威查询绕过缓存，票档库存/销售状态/serverTime保持实时。启用新准入时，新购票先经原安全限流、只读幂等查重，再由Lua原子判断全局及SKU速率；Redis网络IO不得放入受理写事务，不预扣真实库存或替代MySQL容量。新增票档准入不收费既有请求回放，原用户安全限流仍独立生效。缓存默认开启；本机A/B显示准入未获稳定性能收益，准入默认关闭，仅显式启用并按负载验证。Redis查询故障受控回源，受保护购票写入故障关闭。V1至V8不可修改。
- 第十六阶段仅新增V9，历史库必须停写→备份→执行scripts/audit-user-session-quota.sql及既有预检→显式ORDER_QUOTA_MIGRATION_APPROVED=true单次切换→逐项额度对账→放写，完成后撤销开关。历史异常或超限停止，DDL失败保留现场，不repair、不自动修复。开发库V7未授权升级。受理侧PurchaseAdmission及恢复仍保留账号UPDATE锁与原幂等/容量协议；不宣称该侧已去串行。取消响应投影在独立状态Bean事务提交后读取。票档条件UPDATE实验无一致收益，生产保留显式FOR UPDATE，不引入销售版本、库存分桶或Outbox异步确认。
- Docker 优先复用已有兼容镜像。Compose 默认 `pull_policy: missing`，不要无故拉取或更新镜像；完全离线使用 `--pull never`。
- 第八阶段压测只使用scripts/performance固定的ticketing-perf独立Compose、ticketing_perf库及随机环境标记，禁止对开发或真实业务库清理/注入故障。先保留基线，再用同场景、数据量和配置对照；没有稳定收益的调整不修改默认值。MySQL持久化、锁序、签名和鉴权不得为性能指标降级。
- 压测稳定统计按请求完成时间截取预热后固定窗口，409须匹配预期业务错误码，5xx/超时/未知冲突单独失败。每轮一致性快照审计库存、订单、支付、限购、候补及电子票；报告同时记录Outbox积压和已投递延迟，不能用部分SENT样本掩盖积压。k6摘要必须删除setup_data，禁止导出Cookie、密码、有效凭证或签名密钥；业务标识不作为高基数标签。
- Outbox健康批量默认100，可通过WAITLIST_OUTBOX_BATCH_SIZE配置1至100；消息投递故障后结束本轮，按既有退避/租约恢复，不连续认领大批故障事件。发布确认、递增认领令牌、至少一次与数据库幂等语义不变。后续工程调整必须保留第八阶段测量口径与原始结果，不自动开发真实支付或退款。
- 第十六阶段后审查已明确授权有界批量认领和流水线Confirm；WAITLIST_OUTBOX_IN_FLIGHT_WINDOW默认16，可配置1至64且实际不超过健康批量。Rabbit回调只递交有界完成通知，落账由调度线程完成，禁止数据库事务内等待确认。单条失败不妨碍已在途健康消息，本轮不再认领新窗口；超时/关闭后的记录仍由持久租约及递增令牌恢复。普通新受理先读既有幂等事实，再做独立短只读事务销售预检；不得让非事务同步范围保留预检连接直到受理写事务。本人GET购票申请按键查询只恢复事实，不绕过POST安全限流。V1至V9均不可修改，开发库仍未授权升级。
- 第十四阶段普通HTTP下单只负责可靠受理，请求与Outbox及容量预算同事务，返回202；普通建单仅由购票命令消费者驱动。TradeResourceLocker维护交易锁序，OrderReservation共用预占/限购/幂等，OrderStateService编排订单终态。候补与电子票实现OrderLifecycle，在当前事务中先候补后出票；不得改成提交后异步联动，异常必须回滚。
- 业务包保留逻辑协作边界：候补与核销只使用交易公开契约、DTO、模型及OrderReservation/TradeResourceLocker，不访问交易内部服务、Mapper或实体；交易不依赖候补和核销实现。受理不依赖交易实现，交易通过受理协作边界原子写入最终状态。库存条件SQL由TicketSkuMapper/TicketInventory负责。所有生产类须在测试资源architecture-modules.properties登记业务归属，ArchitectureRulesTest检查业务包匹配、归属完整、依赖无环及协作边界，不得关闭规则或用错误归属掩盖依赖。
- V5增加候补预留订单与SKU的复合外键及必需索引，保留原用户外键。部署前只读执行scripts/audit-waitlist-relations.sql，异常只报告并停止，不自动删除、覆盖或repair。数量及幂等关系继续由事务协作核对。第九阶段完成整体回归、迁移、性能复测及联调后停止。
- 数据库 DATETIME(6) 统一为 UTC；外部日期带明确时区，用户界面使用 `zh-CN` 和 `Asia/Shanghai`。金额为人民币分整数，展示为 `¥` 与两位小数；BIGINT ID 通过字符串传输。
- 用户可见文案统一简体中文；技术状态映射为中文，不泄漏内部异常或凭据。
- 每次编码后执行与变更相称的检查，并用中文报告实际测试结果和未能验证的内容；不得宣称未经测试的并发指标或业务能力。
- 用户指定直接在 `main` 开发和推送，不新建分支。远端为 `https://github.com/WGMJJM/ticketing.git`，不得推到其他项目或另建仓库。
- 按用户最新要求先完善全部本地代码并完成整体测试与联调，再按功能点分批提交并依次推送 `main`，不在未完成阶段逐模块推送，也不将全部交付合为唯一一次推送。提交信息使用中文且不含半角或全角冒号。只暂存本次功能文件，不提交 `.env`、日志或构建产物。
- 本仓库 Git 作者为已确认的 GitHub 账户 `WGMJJM`，邮箱 `2829827503@qq.com`；仅设置仓库本地配置。不得强推、覆盖远端提交或绕过保护规则。
- README 保留在本地，根目录 `/README.md` 必须被 `.gitignore` 忽略，不得提交或推送到远程；需要共享的开发与验收说明放在 `docs/`。

## 常用验证

第十九阶段最新部署事实：用户于2026年10月25日明确批准本机`ticketing-mysql-1`的`127.0.0.1:3307/ticketing`在本次停写窗口使用已真实恢复验证的备份升级V8/V9/V10；现已完成V10、原业务字段及全部SQL审计零偏差、撤销一次性批准后的启动检查。此前“开发库仍V7、未授权升级”的阶段记录保留为历史事实，不能作为当前版本。未授权生产上线；后续数据库变更仍须新的具体授权。证据见`docs/31-final-acceptance-and-delivery.md`。

当前真实集成回归统一使用`python -B scripts/performance/verify_backend.py --label <唯一标签>`及固定`ticketing-perf`随机guard/schema。下面`-VerifyDatabase`是继承环境的底层入口，不得加载业务`.env`对原`ticketing`库运行测试或故障注入。

普通后端测试：`mvn -f backend/pom.xml clean verify`，不依赖本地中间件。

真实 MySQL 验证：先启动 Compose，再执行 `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/start-backend.ps1 -VerifyDatabase`；该脚本导入 `.env` 并启用 `mysql-it` profile。

前端：`cd frontend` 后执行 `npm ci`、`npm run build`。构建包含严格 TypeScript 检查。不要把依赖安装当作构建完成。
