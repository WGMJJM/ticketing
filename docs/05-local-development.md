# 本地开发与验收

当前消息链路及 V16 升级要求以 [架构说明](01-architecture.md) 和 [V16 切换检查](41-inventory-sync-migration.md) 为准。本文后半部分的阶段记录保留历史上下文。

Java源码按`controller`、`service`、`mapper`、`entity`、`dto`、`vo`等技术层放置在`backend/src/main/java/com/ticketing`，保留单Maven模块及唯一Spring Boot应用。新生产类须同步登记`backend/src/test/resources/architecture-modules.properties`中的逻辑业务归属；测试分别检查技术包位置、分层依赖、归属完整性、公开协作边界及业务循环依赖。详细结构见[架构说明](01-architecture.md)。

环境：Java 21、Maven 3.9+、满足 Vite 的 Node（20.19+ 或 22.12+）、Docker Desktop 或 Docker Engine + Compose；Windows 推荐 PowerShell。`mvn -version` 显示的 Java 版本才是 Maven 实际使用版本。

首次复制 `.env.example` 为 `.env` 并填写密码与两类独立签名/限流密钥；当前本机已有凭据时无需覆盖。示例默认Secure Cookie为true，本机HTTP联调须在私有`.env`显式设`SESSION_COOKIE_SECURE=false`。有数据的旧库先按[受控升级与运维](32-single-instance-operations.md)核对版本及备份，不能直接启动代替升级审批。Windows 在项目根目录执行：

```powershell
docker compose --env-file .env -f deploy/compose.yml up -d --wait
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/start-backend.ps1
```

另开终端执行 `cd frontend`、`npm ci`、`npm run dev`。访问 `http://127.0.0.1:5173`，后端默认 `http://127.0.0.1:8080`。

macOS/Linux 使用同样的 Compose 命令，后端执行 `bash scripts/start-backend.sh`；前端启动方式相同。后端脚本负责加载根目录 `.env`。`mvn -f backend/pom.xml clean verify` 运行基础测试；真实数据库验收使用下述固定隔离入口；前端 `npm run build` 包含类型检查。

## 配置

注册现要求真实邮箱验证码。SMTP或独立验证码密钥未配置时发送/验证返回503，既有用户登录不受开关影响。配置项和V12升级前提见[注册升级说明](36-registration-four-points.md)。本机最后一次已授权迁移为V15，见[架构说明](01-architecture.md)；当前源码包含V16，启动前必须取得该次升级授权并完成[迁移检查](41-inventory-sync-migration.md)，不能将历史V13启动记录当作当前数据库版本。

从 `.env.example` 生成 `.env` 并配置三个密码；本次初始化已生成随机本地密码。`.env`、`.local`、构建目录被忽略。不要把 `docker compose config` 的完整输出贴到公开日志，它会展开凭据；日常使用 `config --quiet`。

Compose 默认数据库 `ticketing`，应用用户 `ticketing`；Flyway 用该开发用户建表。生产环境应拆分迁移账号与运行账号，本阶段不涉及生产部署。镜像缺失才拉取；若机器本地使用其他兼容标签，先通过 `docker image ls` 确认，再修改 `.env`，不能直接改用不兼容的主版本。

PowerShell 脚本为 UTF-8 BOM，Bash 为 LF UTF-8。脚本读取 `.env` 而不执行文件内容；IDE 不经过脚本时自行注入变量。端口与代理需要一致；浏览器访问 Vite 5173，后端连接由 Vite `/api` 和 `/actuator` 代理完成，无须关闭浏览器安全设置或随意开放 CORS。

## 验收顺序

1. `docker compose --env-file .env -f deploy/compose.yml config --quiet`。
2. `docker compose --env-file .env -f deploy/compose.yml up -d --pull never --wait`，核对三个容器健康，镜像必须事先存在。
3. `mvn -f backend/pom.xml clean verify`，检查不依赖中间件的响应与 MVC/Security 测试。
4. 需要真实集成验证时，先另行配置独立功能验收资源，再使用[功能验证入口](40-functional-verification.md)；不能向业务库加载测试夹具。
5. 启动后端和前端，检查健康状态、Vite代理、首页演出列表和人气榜。
6. 暂停后端，检查活动加载错误和状态连接失败；恢复后端再重试。
7. 检查 `/about`、活动详情、场次选择及运营管理。按最新要求，页面使用正常产品文案；支付与退款确认时说明模拟处理和不发生实际资金往来。登录后下单、支付、查看成功流水，再创建一单等待自动过期，核对库存及限额恢复。当前展示文案调整见[正常展示文案验收](38-normal-display-copy.md)。
8. `npm run build` 同时完成 TypeScript 检查和生产打包。

数据库测试必须在独立验收库运行，入口见[功能验证说明](40-functional-verification.md)。`DatabaseMigrationIT` 检查当前结构，`InventorySyncMigrationIT` 在独立 V15 库验证 V16 切换及阻断，其他指定版本的迁移测试保留历史表引用。`WaitlistManagementIT` 使用统一购票队列验证 FIFO、队首阻塞、状态联动和恢复；旧 Outbox 事件原子性、Confirm 和认领租约不是当前候补验收目标。历史执行数量只属于各阶段报告，不能据此宣称 V16 测试通过。

业务回归默认关闭购票消费者及自动后台任务；候补 IT 显式启用统一购票消费者，使用 `ticketing.purchase.waitlist.it` 交换机及 `.orders` 队列，逐例控制消费并只清理所属隔离夹具。当前无独立候补监听器或库存 Outbox 发布器。未来真实测试仍须固定专用实例、随机 guard 和独立库，不能与业务资源混用；本轮不运行这些测试。

## 账号初始化与本地认证

旧开发Basic管理员已删除，普通用户与管理员统一使用数据库账号和Session。先更新被忽略的 `.env`：本地HTTP设 `SESSION_COOKIE_SECURE=false`；生产HTTPS保持true（后端默认true）。不沿用旧DEV_ADMIN变量。

首次创建管理员时设置 `ADMIN_BOOTSTRAP_ENABLED=true`、`ADMIN_BOOTSTRAP_USERNAME`、`ADMIN_BOOTSTRAP_PASSWORD`，用户名遵循注册格式，密码至少16个字符且UTF-8不超过72字节。启动后端后将开关改回false，并从部署环境移除初始化密码；不把密码放在命令行参数、SQL、前端或Git中。该初始化只创建新ADMIN/ACTIVE账号，已有管理员保持原密码，已有普通/禁用账号则拒绝启动，不自动提权。本机联调生成的随机密码只保存在被忽略的本地.env，未公开输出。

普通用户从 `/register` 注册，成功后前往 `/login`；`/profile`展示当前数据库身份。管理员可用同一登录页，或直接访问 `/admin/login`。刷新通过auth/me恢复Session；会话空闲30分钟或后端重启后需重新登录。退出会使旧Cookie对应的会话失效。每次写请求前获取CSRF，登录和退出后重新获取。不要用浏览器存储保存密码或Basic凭据。

## 演示数据

需要中文样例时，在项目根目录人工执行：

```powershell
docker cp scripts/demo-data.sql ticketing-mysql-1:/tmp/ticketing-demo.sql
docker compose --env-file .env -f deploy/compose.yml exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql --default-character-set=utf8mb4 -u "$MYSQL_USER" "$MYSQL_DATABASE" < /tmp/ticketing-demo.sql'
```

脚本仅导入本地虚构样例活动和场次，不建立票档、订单等数据，也不会由通用 Flyway 迁移自动执行。按用户最新要求，样例使用正常展示名称，数据来源保留在脚本注释和开发说明中，不据此宣称为真实演出。首次不导入时页面显示空状态。封面可留空，或在图片失败时显示稳定分类占位。已有旧名称的数据不会被导入脚本自动改写，也不应重复导入；本次本机文字更新已另获具体授权。

## 票档模块本地验收

本段对应早期V5验收，当前迁移上限为V10；原开发库第十九阶段的升级结果见最终报告。V3票档验收历史见 `docs/08-ticket-inventory-verification.md`。原有销售状态单元测试、票档HTTP集成测试及并发编辑冲突测试继续回归。需要票档样例时，仅在明确的演示库手动将 `scripts/demo-tickets.sql` 复制到容器并按上文相同方式导入，不会由Flyway自动导入。

用户在活动详情选择场次后查看票档；管理员在活动管理中打开“场次”，再进入“票档管理”。启用销售前需确认销售窗口；已启用时仅能修改总库存，下架后可改其他基本信息。表单输入人民币元，接口发送整数分字符串。状态以服务端查询结果为准，可点击刷新获取最新结果。

## 订单模块本地验收

订单配置在application.yml集中声明：`ORDER_SESSION_LIMIT`默认4，`ORDER_PAYMENT_WINDOW_MINUTES`默认15。启动脚本读取.env；本地已有.env无需覆盖，未指定使用默认值。只允许正数。降低限额不会撤销既有订单，占额超出新限额时阻止继续下单，仍可主动取消。

从活动详情选择正在售票的票档，未登录会跳转登录并返回活动；数量上限取库存、单笔及场次剩余额度的最小值。提交真实订单后查看 `/orders/{id}` 与 `/orders`，页面刷新重新查身份及订单。立即支付前核对活动、场次、票档、数量和金额，确认后显示PAID与真实模拟流水，不产生扣款。确认取消后查看CANCELLED，回活动页刷新库存及额度。创建另一单等待截止，截止即禁用支付按钮，后台转EXPIRED后刷新展示已过期、库存与额度恢复。支付仍占场次限额；截止提示不等于已关闭。尚无退款或真实支付。

## 模拟支付与超时扫描配置

ORDER_EXPIRY_ENABLED默认true，ORDER_EXPIRY_SCAN_DELAY_MS默认30000，ORDER_EXPIRY_BATCH_SIZE默认100（1至500）。逐笔独立事务处理，释放成功同事务递增库存同步版本，后台同步Redis及扫描候补。

快速浏览器验收可只在启动进程临时设置ORDER_PAYMENT_WINDOW_MINUTES=1及ORDER_EXPIRY_SCAN_DELAY_MS=1000，等待真实截止并刷新状态；不要用修改订单状态SQL代替验收。验证后恢复默认15分钟/30秒，不提交本地.env。积压、锁等待和错误重试会造成关闭延迟，支付SQL仍严格按数据库截止拒绝。测试注入的库存/流水写入故障仅限测试代码，不出现在产品接口。

## 候补与消息验收

WAITLIST_MAX_WAIT_MINUTES默认60，实际申请截止不晚于票档停售。WAITLIST_COMPENSATION_POLL_MS默认5000，WAITLIST_JOBS_ENABLED默认true；候补按持久FIFO扫描并复用普通购票队列。

先由用户B在仅剩1张的票档创建待支付订单，再由用户A申请1张候补；B取消后，核对MySQL库存版本、Redis余额及后台扫描产生的候补订单。A可在关联订单完成模拟支付，核对locked=0、sold=1及唯一支付流水。分配前只可取消候补，分配后须前往订单取消。

检查普通购票队列、恢复索引和库存版本差异；未决队首阻挡后续候补，重复扫描不得重复分配。Redis账本缺失时停卖核对，不通过删除数据卷模拟恢复。

## 电子票签名与核销

第七阶段启动前必须配置TICKET_CREDENTIAL_KEYS和TICKET_CREDENTIAL_CURRENT_VERSION；缺失或格式错误会拒绝启动，不提供固定默认密钥。本机已生成随机密钥保存在被忽略的.env，无需再次覆盖。新环境可使用密码学安全随机数生成至少32字节，再Base64编码，构造版本至密钥的JSON映射写入私有环境配置；不要把真实值写入示例、终端输出、Git或浏览器。

PowerShell只在尚未配置的新环境生成密钥，可在本地执行下面代码；它只追加私有.env，不打印密钥。已有配置不要重新运行覆盖：

```powershell
$ticketKeyBytes = New-Object byte[] 32
[System.Security.Cryptography.RandomNumberGenerator]::Fill($ticketKeyBytes)
$ticketKeyText = [Convert]::ToBase64String($ticketKeyBytes)
$ticketKeyMapping = 'TICKET_CREDENTIAL_KEYS={"1":"' + $ticketKeyText + '"}'
[IO.File]::AppendAllText((Join-Path (Get-Location) '.env'), "`n" + $ticketKeyMapping + "`nTICKET_CREDENTIAL_CURRENT_VERSION=1`n", [Text.UTF8Encoding]::new($false))
```

轮换时保留旧有效版本映射，加入新随机版本并更新当前版本，重启后新票使用新版本；旧票仍通过旧密钥重建及校验。密钥丢失不能从数据库摘要还原，恢复原版本密钥，不能删除旧票重发来绕过。测试上下文自动生成独立随机密钥，不与真实本地环境混用。

用户支付后从“查看本单电子票”或“我的电子票”进入，同订单多张分别编号和展示二维码。二维码本地编码，完整凭证不放URL或浏览器存储；刷新/隐藏/切换会清理二维码。已核销显示时间且不展示有效二维码，作废票同样不可用。所有票券、订单和核销查询使用真实Session及本人归属约束。

管理员从运营后台进入“电子票核销”，可用摄像头、选择二维码图片或手动粘贴。摄像头要求安全上下文及用户授权，识别后停扫并人工确认；失败时替代入口仍可用，不能绕过浏览器权限。CHECKIN_OPENS_BEFORE_MINUTES默认60、范围0至1440，后端根据MySQL时间最终校验至场次结束。重复扫码409，不改变首次人员/时间；核销不调整库存或支付状态。二维码生成使用[qrcode](https://github.com/soldair/node-qrcode)，浏览器识别使用[ZXing Browser](https://github.com/zxing-js/browser)，无需外部二维码生成服务。

## 受控历史补票

TICKET_BACKFILL_ENABLED默认false；只有明确需要时在本地私有环境或受控进程设置true。TICKET_BACKFILL_BATCH_SIZE默认100、上限500，TICKET_BACKFILL_SCAN_DELAY_MS默认30000。启动即扫描PAID订单，每单独立事务，只补缺失序号，检查已有票一致性；已用和作废票保留，异常记录订单ID并继续后续，游标到尾回绕重试。完成补票核对后关闭开关并重启，不暴露公开补票接口。

第七阶段没有额外迁移；第九阶段新增V5候补关联约束，电子票结构保持原样。前端真实联调先在可核销窗口的演示场次购买两张并模拟支付，分别展示两张二维码；管理员识别第一张、确认核销、重复识别确认应显示已核销，再识别第二张独立成功，用户刷新两张均已核销。不要通过修改已付订单或票券状态伪造验收。

第七阶段验收时42项基础测试加108项真实集成测试共150项，包含ElectronicTicketIT的24项新用例及签名/轮换配置测试，详见docs/14-electronic-ticket-verification.md。测试结束清理的是本用例生成的票券夹具，不提供产品删除票券能力。数据库测试会调用真实支付出票，必须停止运行中的本地后端再执行，避免后台任务和随机测试密钥处理真实订单。

## 第九阶段架构与迁移验收

订单写事务仍由OrderService入口开启，候补及核销走TradeResourceLocker公开锁API，库存使用TicketInventory，同步OrderLifecycle参与者不可改成提交后异步处理。四组非敏感配置集中绑定，旧环境变量及属性名继续使用；消息包迁到messaging不会改变MQ交换机、队列或历史消息。

上线V5之前暂停业务写入，只读执行scripts/audit-waitlist-relations.sql，发现SKU、用户、数量或场次差异只报告并停止，不能删除历史记录或自动repair。已有V1至V4不改，V5在原表添加复合引用索引及外键。独立探针复现命令：

本轮普通Maven为50项，真实集成111项，失败/错误/跳过均0；前端npm ci及构建通过。指定性能复测、浏览器组件与凭证清理验收的实际结果见[第九阶段记录](16-architecture-verification.md)。

## 第十一阶段工作人员及管理审计

本节记录第十一阶段权限和V7迁移协议。先在已有Flyway库中只读执行`scripts/audit-staff-audit-readiness.sql`；STOP结果必须停止升级并人工核对。当前真实集成统一使用固定perf实例与随机guard，运行`python -u -B scripts/verification/verify_backend.py --label <新标签>`，不要用会导入根.env的`start-backend.ps1 -VerifyDatabase`替代隔离。迁移探针需要在独立验收实例中创建随机schema的权限，运行应用的普通账号不需要全局建库权限。本地MySQL启用binlog时，Compose设置`log_bin_trust_function_creators=1`，让具备当前schema TRIGGER权限的迁移账号创建仅追加审计触发器；部署环境须由数据库管理员按本地权限策略配置等效权限。历史V1～V10均不可改写；迁移失败保留数据库现场，不自动repair或清库。

运营管理员登录`/admin/staff`，从已注册的普通ACTIVE账号中选择工作人员并提升为CHECKIN_STAFF；选择正常场次授予核销权限。工作人员登录`/staff/checkin`后只会看到自己的有效场次。用普通用户在真实演出窗口内购买并模拟支付，再以工作人员账号扫码；有效授权的票核销成功，未授权场次返回无权提示，重复核销显示已核销且不会覆盖原工作人员和时间。管理员继续从运营后台核销入口处理任意场次，现有`/api/v1/admin/checkins`行为不变。

在工作人员仍保持登录时，管理员从`/admin/staff`撤销其场次权限，再用原Cookie重试同一测试场次：服务端须返回403，工作人员页面场次列表同时不再包含该场次。降级到USER或停用账号会撤销全部有效授权；下一次受保护请求按数据库最新角色刷新身份或使会话失效。工作人员访问订单、退款、活动/票档管理和`/admin/audit-logs`应为403；USER不能管理或核销，管理员才能管理授权和查看审计。

审计检查从`/admin/audit-logs`按操作人、动作、资源或时间筛选，确认活动/场次/票档/库存、工作人员角色/授权、管理员退款均记录了相应旧值和新值。核销人员与时间从电子票事实查看。审计不包含凭证、密码、Cookie或CSRF。失败业务事务不能留下一条成功审计记录；并发修改须核对每条记录的前后快照与实际提交顺序，这些行为由`StaffAuditIT`和真实MySQL集成验收覆盖，不要用手改数据库代替。

## Git 与本地文件

仅在 main 开发；先完成本地代码、测试和联调，再提交并推送到用户指定的 WGMJJM/ticketing。README.md 只保留在本地并被忽略，远程共享说明在 docs。`.env`、`.local`、构建产物均不得提交。

## 排障与停止

Compose 日志用 `logs --tail 80 <服务>`；后端启动失败优先检查 MySQL、密码与 Flyway 报错，不通过关闭迁移绕过。Redis 与 MQ 故障会影响聚合健康但系统状态仍可能可访问。日志与 JSON 不应输出明文凭据和内部堆栈给前端。

前后端开发终端用 Ctrl+C；容器用 Compose `stop`，保留数据。不要为修复密码或迁移问题直接删除数据卷。未应用的迁移可以在初次验证前修正；一旦 V1 已应用，所有后续数据库变更使用新版本。

## 第十二阶段Redis限流配置

运行前为`RATE_LIMIT_KEY_SECRET`注入独立随机Base64密钥（解码32至128字节），不复用电子票密钥、不写入版本库。PowerShell可在当前进程生成：

```powershell
$rateLimitKeyBytes = New-Object byte[] 32
$rateLimitGenerator = [Security.Cryptography.RandomNumberGenerator]::Create()
try {
    $rateLimitGenerator.GetBytes($rateLimitKeyBytes)
    $env:RATE_LIMIT_KEY_SECRET = [Convert]::ToBase64String($rateLimitKeyBytes)
} finally {
    $rateLimitGenerator.Dispose()
}
```

生产默认`RATE_LIMIT_ENABLED=true`，多应用实例共享同一命名空间和密钥；环境之间使用不同的`RATE_LIMIT_NAMESPACE`。变更密钥/命名空间会重置短期预算，须作为受控部署操作。配置位于`.env.example`、`application.yml`的`ticketing.security.rate-limit`，启动严格校验阈值1～10000、窗口1～3600秒、命名空间1～32个字母/数字/下划线/连字符、故障Retry-After为1～60秒。Redis连接和命令超时默认各500ms。

| 环境变量前缀 | LIMIT默认 | WINDOW_SECONDS默认 |
| --- | ---: | ---: |
| `RATE_LIMIT_LOGIN_IP_` | 60 | 60 |
| `RATE_LIMIT_LOGIN_ACCOUNT_IP_` | 10 | 60 |
| `RATE_LIMIT_REGISTER_IP_` | 10 | 600 |
| `RATE_LIMIT_ORDER_USER_` | 30 | 10 |
| `RATE_LIMIT_WAITLIST_USER_` | 10 | 60 |
| `RATE_LIMIT_CSRF_SESSION_IP_` | 120 | 60 |

另有`RATE_LIMIT_UNAVAILABLE_RETRY_SECONDS=1`。生产Redis宕机时以上四个提交入口返回503；公开查询、本人已有订单查询以及数据库补偿任务保持原逻辑。不要以关闭保护作为常规故障处理。恢复Redis后连接自动重试；Redis自身重启可能丢失短期计数，MySQL事实不变。部署Compose继续复用`redis:7-alpine`及现有AOF卷，无新设施或Flyway迁移。

`server.forward-headers-strategy=none`必须保持，应用启动会检查。默认`CLIENT_ADDRESS_MODE=DIRECT`只使用Servlet连接地址，忽略全部转发头。第十三阶段支持显式`CLIENT_ADDRESS_MODE=TRUSTED_PROXY`，`CLIENT_ADDRESS_TRUSTED_PROXIES`为逗号分隔的数值IP/CIDR列表；仅信任实际socket代理地址，禁止`/0`和域名。默认最多8跳、1024字符，可通过`CLIENT_ADDRESS_MAX_FORWARDED_HOPS`（1～32）和`CLIENT_ADDRESS_MAX_FORWARDED_HEADER_LENGTH`（64～4096）调整。DIRECT模式不允许同时配置代理列表，TRUSTED_PROXY模式必须配置列表。

边缘代理必须覆盖外部X-Forwarded-For、清除X-Real-IP/Forwarded，并通过网络隔离使后端只接受该代理；示例见`deploy/nginx.conf.example`。多层代理须逐层清洗并准确配置受信节点，应用从最近代理向外解析，不能简单取最左地址。受信源的格式异常会400，非受信源的伪造头会被忽略。代理模式不能替代防火墙或TLS。新会话预算在CsrfFilter前执行，已有Session复用不依赖该预算；无Session写请求也消耗该预算，因此Redis故障时首次登录/注册的令牌获取会503。

普通后端回归在测试资源中显式关闭限流，不连接Redis；`mysql-it`中的`RateLimitIT`、`CsrfSessionIT`和`TrustedProxyIT`显式开启，默认使用本机16379，`RATE_LIMIT_TEST_REDIS_PORT`可指定隔离Redis，只清理各自随机命名空间，不执行FLUSHDB/FLUSHALL。真实MySQL测试须使用独立验收库。原开发库已在第十三阶段按单次明确授权完成受控V7恢复；历史证据保留，部署仍须先只读执行三个审计脚本，STOP时禁止自动repair或继续部署。恢复工具和权限探针的使用条件见[阶段十三验收](23-session-proxy-migration-verification.md)。

## 第十五阶段目录缓存及票档准入

公开活动/场次缓存由`CATALOG_CACHE_ENABLED`控制，默认开启；L1最多2000项/15秒，L2为120秒加至多约10%抖动，负缓存5秒。有界列表为无关键词、常用城市、前3页、12/20条，其他组合直接查库。票档库存、销售窗口和serverTime保持实时。管理提交后换代失效，Redis故障受控回源；相关阈值见`.env.example`及`ticketing.catalog-cache`。

本阶段不新增Flyway迁移；开发库仍须完成V8部署预检再升级。详细协议及验证见[第十五阶段记录](26-redis-catalog-and-ticket-admission-verification.md)。

## 第十六阶段额度迁移

限购仍使用`ORDER_SESSION_LIMIT`，默认4；运行限额查询和交易改读用户场次独立额度，支付不重复占额，取消/过期/退款成功才释放。普通受理保持异步协议及原用户排他锁，交易侧使用共享账号协调和精确场次额度锁。

已有历史订单且尚未应用V9时，默认启动预检报`STOP_QUOTA_SWITCH`，不会自动进行额度切换。必须停写并备份，使用同一只读连接先`SET @ticketing_quota_limit=<实际ORDER_SESSION_LIMIT>`再执行`scripts/audit-user-session-quota.sql`，并完成既有关系/退款/管理审计/受理预检。无异常后单次设置`ORDER_QUOTA_MIGRATION_APPROVED=true`迁移；校验全部额度组与有效订单SUM一致后撤销开关、恢复写入。失败现场保留，不执行repair或清库。

第十六阶段验收时原开发库仍为V7，当时只读核对而未部署V8/V9；该结论属于历史记录。第十九阶段已在用户明确批准的本次停写窗口，使用恢复验证过的V7备份将原开发库升级V10，对账通过并撤销单次批准；详细结果见[最终验收](31-final-acceptance-and-delivery.md)。其他有数据环境仍须逐库获得授权，不能直接用普通启动命令替代受控升级。第十六阶段的固定隔离基准与真实结果见[历史验收](27-trade-quota-and-transaction-verification.md)。

## 第十六阶段后审查（历史记录）

Outbox增加`WAITLIST_OUTBOX_IN_FLIGHT_WINDOW`，默认16、允许1至64，实际认领数量还受原健康批量1至100限制。每个窗口先短事务认领，再流水线发送及异步Confirm通知，调度线程独立落账；30秒租约必须超过5000毫秒确认超时并预留至少一秒。故障后不认领新窗口，未确认记录保持可由数据库租约恢复。不要通过扩大窗口替代端到端测量，64档未重复出稳定收益。

新申请的实时预检不占库存，使用独立短只读事务以及时归还MyBatis连接；原同键回放优先，原安全限流和消费者最终检查保持。只读`GET /purchase-requests/lookup`使用Idempotency-Key请求头恢复本人申请。此次不新增迁移，V1至V9冻结，开发库继续遵守原升级门禁。审查结果、失败样本和复现命令见[后审查记录](28-post-stage16-audit-and-remediation.md)。
