# 本地开发与验收

环境：Java 21、Maven 3.9+、满足 Vite 的 Node（20.19+ 或 22.12+）、Docker Desktop 或 Docker Engine + Compose；Windows 推荐 PowerShell。`mvn -version` 显示的 Java 版本才是 Maven 实际使用版本。

首次复制 `.env.example` 为 `.env` 并填写密码；当前本机已生成随机凭据，无需覆盖。Windows 在项目根目录执行：

```powershell
docker compose --env-file .env -f deploy/compose.yml up -d --wait
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/start-backend.ps1
```

另开终端执行 `cd frontend`、`npm ci`、`npm run dev`。访问 `http://127.0.0.1:5173`，后端默认 `http://127.0.0.1:8080`。

macOS/Linux 使用同样的 Compose 命令，后端执行 `bash scripts/start-backend.sh`；前端启动方式相同。后端脚本负责加载根目录 `.env`。`mvn -f backend/pom.xml clean verify` 运行基础测试；PowerShell 脚本加 `-VerifyDatabase` 或 Bash 脚本加 `--verify-database` 执行真实数据库测试；前端 `npm run build` 包含类型检查。

## 配置

从 `.env.example` 生成 `.env` 并配置三个密码；本次初始化已生成随机本地密码。`.env`、`.local`、构建目录被忽略。不要把 `docker compose config` 的完整输出贴到公开日志，它会展开凭据；日常使用 `config --quiet`。

Compose 默认数据库 `ticketing`，应用用户 `ticketing`；Flyway 用该开发用户建表。生产环境应拆分迁移账号与运行账号，本阶段不涉及生产部署。镜像缺失才拉取；若机器本地使用其他兼容标签，先通过 `docker image ls` 确认，再修改 `.env`，不能直接改用不兼容的主版本。

PowerShell 脚本为 UTF-8 BOM，Bash 为 LF UTF-8。脚本读取 `.env` 而不执行文件内容；IDE 不经过脚本时自行注入变量。端口与代理需要一致；浏览器访问 Vite 5173，后端连接由 Vite `/api` 和 `/actuator` 代理完成，无须关闭浏览器安全设置或随意开放 CORS。

## 验收顺序

1. `docker compose --env-file .env -f deploy/compose.yml config --quiet`。
2. `docker compose --env-file .env -f deploy/compose.yml up -d --pull never --wait`，核对三个容器健康，镜像必须事先存在。
3. `mvn -f backend/pom.xml clean verify`，检查不依赖中间件的响应与 MVC/Security 测试。
4. `scripts/start-backend.ps1 -VerifyDatabase` 或 Bash `--verify-database`，运行真实 MySQL 集成测试；首次连接空库执行 V1，后续执行校验。不会用 H2 替代。
5. 启动后端和前端，检查状态接口、Actuator 与 Vite 代理，确认首页为「服务运行正常」。
6. 暂停后端，检查活动加载错误和状态连接失败；恢复后端再重试。
7. 检查 `/about`、活动详情、场次选择及运营管理。演示活动须显式标记；登录后真实下单、模拟支付、查看成功流水，再创建一单等待自动过期，核对库存及限额恢复；不产生实际扣款。
8. `npm run build` 同时完成 TypeScript 检查和生产打包。

数据库测试位于 `DatabaseMigrationIT`，推荐独立开发库。`ActivityManagementIT`、`TicketManagementIT`覆盖原有业务；`AuthenticationIT`使用随机端口Tomcat、真实HTTP、Cookie、Session和MySQL完成认证及旧会话撤权，`SessionCookieIT`检查生产Secure属性。`CurrentAccountFilterTest`验证公开端点跳过账号查询及订单/候补等受保护端点刷新身份。`OrderManagementIT`现有25项检查订单预占、幂等、限购、支付、取消、过期竞争、流水、回滚和最终库存；`OrderExpiryRecoveryIT`关闭再启动两个真实应用上下文，验证Scheduling重启补偿。`WaitlistManagementIT`新增25项，使用真实HTTP会话、独立MySQL事务及RabbitMQ检查FIFO、并发入队/调度、队首阻塞、状态联动、事件原子性、确认丢失、认领租约、消费重试/死信及数据库补偿。第六阶段验收为35项基础测试加84项集成测试共119项，历史结果见 `docs/13-waitlist-verification.md`。这些是功能与并发一致性验证，不是吞吐压测。

业务回归默认关闭候补任务和消息；候补IT显式启用消息但停止自动后台任务，使用独立的ticketing.waitlist.it交换机及队列，逐例控制消费并只清空测试队列。夹具按所属用户、场次和SKU清理。Outbox发布器读取当前库待投事件，推荐专用测试库，避免与正在运行的业务实例并行执行；先停止本地后端再验收。测试故障通过Spy在真实事务中注入，队列、数据库和实际库存变化不使用内存替代。

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

脚本仅导入名称标有“演示”的虚构活动和场次，不建立票档、订单等数据，也不会由通用 Flyway 迁移自动执行。首次不导入时页面显示真实空状态。封面可留空，或在图片失败时显示稳定分类占位。

## 票档模块本地验收

当前数据库为V5，V3票档验收历史见 `docs/08-ticket-inventory-verification.md`。原有销售状态单元测试、票档HTTP集成测试及并发编辑冲突测试继续回归。需要票档样例时，手动将 `scripts/demo-tickets.sql` 复制到容器并按上文相同方式导入，不会由Flyway自动导入。

用户在活动详情选择场次后查看票档；管理员在活动管理中打开“场次”，再进入“票档管理”。启用销售前需确认销售窗口；已启用时仅能修改总库存，下架后可改其他基本信息。表单输入人民币元，接口发送整数分字符串。状态以服务端查询结果为准，可点击刷新获取最新结果。

## 订单模块本地验收

订单配置在application.yml集中声明：`ORDER_SESSION_LIMIT`默认4，`ORDER_PAYMENT_WINDOW_MINUTES`默认15。启动脚本读取.env；本地已有.env无需覆盖，未指定使用默认值。只允许正数。降低限额不会撤销既有订单，占额超出新限额时阻止继续下单，仍可主动取消。

从活动详情选择正在售票的票档，未登录会跳转登录并返回活动；数量上限取库存、单笔及场次剩余额度的最小值。提交真实订单后查看 `/orders/{id}` 与 `/orders`，页面刷新重新查身份及订单。立即支付前核对活动、场次、票档、数量和金额，确认后显示PAID与真实模拟流水，不产生扣款。确认取消后查看CANCELLED，回活动页刷新库存及额度。创建另一单等待截止，截止即禁用支付按钮，后台转EXPIRED后刷新展示已过期、库存与额度恢复。支付仍占场次限额；截止提示不等于已关闭。尚无退款或真实支付。

## 模拟支付与超时扫描配置

ORDER_EXPIRY_ENABLED默认true，ORDER_EXPIRY_SCAN_DELAY_MS默认30000，ORDER_EXPIRY_BATCH_SIZE默认100（1至500）。启动立即扫描，逐笔独立事务处理，批次完成再等待间隔；使用MySQL时间与既有到期索引，账号禁用或父级不可售不影响释放。失败会记录订单ID并继续，游标扫到尾回绕重试；重启重新扫描历史PENDING。不引入RabbitMQ延迟关单，释放成功会同事务写候补Outbox事件。

快速浏览器验收可只在启动进程临时设置ORDER_PAYMENT_WINDOW_MINUTES=1及ORDER_EXPIRY_SCAN_DELAY_MS=1000，等待真实截止并刷新状态；不要用修改订单状态SQL代替验收。验证后恢复默认15分钟/30秒，不提交本地.env。积压、锁等待和错误重试会造成关闭延迟，支付SQL仍严格按数据库截止拒绝。测试注入的库存/流水写入故障仅限测试代码，不出现在产品接口。

## 候补与消息验收

WAITLIST_MAX_WAIT_MINUTES默认60，实际申请截止不晚于票档停售。WAITLIST_OUTBOX_POLL_MS默认1000，WAITLIST_COMPENSATION_POLL_MS默认60000；WAITLIST_MESSAGING_ENABLED与WAITLIST_JOBS_ENABLED默认true。Outbox批量、确认超时、租约、总尝试上限、消费退避等集中在application.yml。消息关闭时数据库补偿仍能推进，不能将关闭消息当作正常生产验收结果。

先由用户B在仅剩1张的演示票档创建真实待支付订单，再由用户A申请1张候补；B主动取消后，查询MySQL释放库存及PENDING/SENT事件，RabbitMQ触发A的真实预留订单。A刷新“我的候补”进入关联订单完成模拟支付，刷新候补应显示已完成；核对locked=0、sold=1、唯一支付流水。分配前只可取消候补，分配后须前往订单取消。WAITING不占场次限额，分配重新检查，不保证候补成功或优先于新购票。

消费者队列ticketing.waitlist.dispatch及其.dead均持久化；检查失败日志、FAILED事件和死信数量。修复基础设施后PENDING/超时PROCESSING自动重投，FAILED保留人工排查；不删除业务事件绕过问题。低频扫描会补偿仍有WAITING的SKU，队首不足不越过。端到端为至少一次与数据库幂等。定时任务重启会再次读库，内存游标无需迁移；不要删除Docker数据卷模拟恢复。

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

## 第八阶段性能验证

压测与故障注入只使用固定ticketing-perf项目、13307端口的ticketing_perf库和独立MQ，不导入开发.env。准备、等价重建、六类k6场景、监测、审计和恢复命令见[压测复现](performance/01-reproduce.md)，实际结果见[第八阶段验收](15-performance-verification.md)。清理前必须核对专用库随机环境标记，不允许对真实业务库运行这些工具。

本轮45项基础测试加108项真实MySQL/RabbitMQ集成共153项全部通过，前端npm ci与build通过。52组短时压力、10类恢复检查、4项审计反例及5项隔离边界测试均通过；这些数量不合并冒充后端JUnit数量。原集成验证脚本本轮通过进程变量连接专用测试环境，未改动脚本或根.env。

WAITLIST_OUTBOX_BATCH_SIZE默认100（1至100）；健康时增加每轮发布量，一次故障后结束本轮，继续按既有退避/租约恢复。连接池默认仍为10，无新索引、迁移或Redis库存。不要将本机20秒稳定窗口的数据当作生产容量。

## 第九阶段架构与迁移验收

订单写事务仍由OrderService入口开启，候补及核销走TradeResourceLocker公开锁API，库存使用TicketInventory，同步OrderLifecycle参与者不可改成提交后异步处理。四组非敏感配置集中绑定，旧环境变量及属性名继续使用；消息包迁到messaging不会改变MQ交换机、队列或历史消息。

上线V5之前暂停业务写入，只读执行scripts/audit-waitlist-relations.sql，发现SKU、用户、数量或场次差异只报告并停止，不能删除历史记录或自动repair。已有V1至V4不改，V5在原表添加复合引用索引及外键。独立探针复现命令：

```powershell
python scripts/performance/verify_migrations.py --legacy-jar .local/performance/optimized.jar --jar backend/target/ticketing-backend-0.1.0-SNAPSHOT.jar --label new-validation
```

legacy-jar应为基准2c7b086对应的V4产物；本机保留第八阶段产物，其他环境可在私有目录用git archive导出该版本构建，不覆盖当前工作区。探针仅使用已标记ticketing-perf实例内本次生成的临时schema，并在结束后只清理这些schema，不重建原数据库。标签不能覆盖旧结果。

本轮普通Maven为50项，真实集成111项，失败/错误/跳过均0；前端npm ci及构建通过。指定性能复测、浏览器组件与凭证清理验收的实际结果见[第九阶段记录](16-architecture-verification.md)。

## Git 与本地文件

仅在 main 开发；先完成本地代码、测试和联调，再提交并推送到用户指定的 WGMJJM/ticketing。README.md 只保留在本地并被忽略，远程共享说明在 docs。`.env`、`.local`、构建产物均不得提交。

## 排障与停止

Compose 日志用 `logs --tail 80 <服务>`；后端启动失败优先检查 MySQL、密码与 Flyway 报错，不通过关闭迁移绕过。Redis 与 MQ 故障会影响聚合健康但系统状态仍可能可访问。日志与 JSON 不应输出明文凭据和内部堆栈给前端。

前后端开发终端用 Ctrl+C；容器用 Compose `stop`，保留数据。不要为修复密码或迁移问题直接删除数据卷。未应用的迁移可以在初次验证前修正；一旦 V1 已应用，所有后续数据库变更使用新版本。
