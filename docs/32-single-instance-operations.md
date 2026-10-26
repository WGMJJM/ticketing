# 单实例部署与运维

本文对应第十九阶段。后端只有一个 `backend/pom.xml`、一个 `TicketingApplication` 和一个运行中的 JAR。MySQL、Redis、RabbitMQ 复用 `deploy/compose.yml`；前端构建为静态文件，通过同源 HTTPS 服务转发 `/api` 至本机后端。开发/隔离验收不代表生产上线。

## 从零初始化

1. 安装 Java 21、Maven、满足 `frontend/package.json` 的 Node 和 Docker。复制 `.env.example` 为被忽略的 `.env`；填写独立的 MySQL/Rabbit 密码、限流密钥和电子票签名密钥。两个密钥使用不同的安全随机字节，以 Base64 表达；不要放在 shell 历史或共享报告中。电子票密钥配置为版本号到密钥的 JSON。
2. `docker compose --env-file .env -f deploy/compose.yml config --quiet`；再执行 `docker compose --env-file .env -f deploy/compose.yml up -d --pull never --wait`。此命令复用已有镜像和卷。需要预先准备兼容镜像；不执行 `down -v`。
3. 空库首次启动由 Flyway 顺序应用 V1～V10。迁移账号需要目标 schema 的 DDL、TRIGGER、CREATE VIEW 等权限以及兼容触发器策略；运行账号按业务需要限定该 schema 的查询/写入权限，部署完成后撤销其 DDL 权限。勿向应用授予全局 SUPER。已有 V7 数据按下一节升级，不把它当空库初始化。
4. 首次管理员只通过 `ADMIN_BOOTSTRAP_ENABLED=true`、显式用户名和至少16位随机密码初始化。首次成功后关闭此开关；公开注册永远只创建普通用户。`.env` 及备份仅限操作系统账号读取。
5. 执行 `mvn -f backend/pom.xml clean verify`。在 `frontend` 执行 `npm ci` 和 `npm run build`；发布 `frontend/dist`。本机 HTTP 联调需显式设置 `SESSION_COOKIE_SECURE=false`，生产 HTTPS 使用 true。示例文件现在默认 true。
6. 本地可运行 `scripts/start-backend.ps1`。部署优先使用构建的 `backend/target/ticketing-backend-0.1.0-SNAPSHOT.jar`，将已核对的环境变量注入唯一进程，使用系统现有服务管理器监督它。不要同时运行 Maven 启动和 JAR。
7. 生产可显式加载 `--spring.config.additional-location=file:deploy/application-operations.yml`，使最小诊断集位于已有 ADMIN 路径。它强制 Secure Cookie、回环监听、Hikari 10；不暴露 env、beans、configprops、heapdump 或线程转储。反向代理只开放业务 HTTPS，并清洗客户端转发头。默认 DIRECT 忽略伪造头；需要代理来源识别时，显式配置 TRUSTED_PROXY 与准确的代理地址，后端端口不得允许其他来源直连。

## 有数据升级与恢复

阶段19的真实结果和原开发库授权见 [最终验收](31-final-acceptance-and-delivery.md)。原历史文件 V1～V10 均冻结。升级不改变 SINGLE 默认，也不将历史票档自动分桶。

1. 确认数据库主机、端口、schema、server UUID、当前 Flyway 版本和失败记录；确认具体维护窗口。停止唯一后端、消费者及所有其他业务写入来源。静态维护页不能代替停止后端。
2. 制作含结构、数据、触发器和版本历史的完整备份，保存 SHA-256；在随机隔离 schema 实际恢复，逐表行数及摘要对账。备份含密码哈希和票券摘要，禁止提交仓库。另行保护电子票旧版签名密钥；数据库备份不能替代密钥备份。
3. 在停写快照运行 `audit-waitlist-relations.sql`、`audit-refund-readiness.sql`、`audit-staff-audit-readiness.sql`、`audit-async-purchase-readiness.sql`、`audit-user-session-quota.sql`；额度审计连接显式 `SET @ticketing_quota_limit=4` 或实际运行限额。任何异常停止，保留数据，人工核对。
4. V7 含历史订单时，只有在明确批准的这次切换进程设置 `ORDER_QUOTA_MIGRATION_APPROVED=true`。按 V8、V9、V10 顺序迁移，不使用 repair/clean。迁移失败保留 DDL 和失败历史，不把新表存在当成功，不手改 Flyway 版本。
5. 核对旧列逐行摘要、V8幂等映射、V9双向额度、V10默认策略与表/索引/约束/触发器/视图；执行完整库存、订单、支付、退款、候补、票券及受理审计。所有偏差必须为0。
6. 撤销一次性批准，再启动验证健康、Session/CSRF、公开查询和受保护接口。确认审计及维护验收通过后才放写。`scripts/upgrade-v7-v10.py`提供已核对本机原开发库的受控执行入口，默认仅校验参数；它不是任意生产库的通用升级工具。
7. 若失败，保持停写，保留现场、日志及原备份。在新的数据库恢复已验证备份并对账，由负责人决定连接切换和重新开放；不要对失败现场自动回退DDL、删除对象或强行降级。真实生产恢复切换需要另行授权。

以下仅示意受控升级工具的只读参数检查，变量须指向另行授权维护窗口内有效的JAR、恢复报告及已验证备份。旧阶段临时文件已清理，不能继续照搬历史路径；当前本机版本为V13，见[本机启动记录](37-local-v13-startup.md)。

```powershell
python scripts/upgrade-v7-v10.py --jar $verifiedJar --verified-report $verifiedReport --backup $verifiedBackup --backup-sha256 $backupSha256 --label $runLabel
```

实际变更入口额外要求`--apply --confirm-database ticketing --maintenance-confirmed`，并核对原库停写、原字段快照和迁移历史；这些参数不能代替负责人对具体窗口的批准。本轮两个续行参数只处理完整V7的启动预检失败或已完整V10的启动配置失败，拒绝失败DDL；它们不是通用故障修复开关。

## 启停和健康检查

正常关停由服务管理器向唯一 Java 进程发送终止信号并等待退出。Outbox未完成记录保留持久租约；候补本地队列只保存触发，WAITING事实在MySQL。异常中止后恢复中间件，再启动一个JAR；过期、售后、受理和候补任务会扫描数据库。不要用队列清空、改订单状态或修改库存账本来“恢复”。

`python scripts/check-deployment.py --base-url http://127.0.0.1:8080 --output .local/deployment-check.json`执行只读业务/健康检查，结果文件须使用新名称。HTTPS部署增加`--require-https`。装配受保护管理诊断路径时增加`--admin-monitoring`，通过进程环境提供`TICKETING_SMOKE_USERNAME/PASSWORD`。生产内部诊断使用现有管理员Session，沿用CSRF、最新账号/角色校验和网络边界。

原始默认配置的 `/actuator/health` 仅公开健康汇总，`/system/status` 只是进程响应，不能证明三个中间件可用。装配内部管理路径后健康也要求ADMIN。不要在互联网开放原始Actuator。

## 日常观测与处理

| 观察项 | 触发与处理 |
| --- | --- |
| MySQL/Redis/Rabbit 连接 | health非UP、连接异常即告警；先恢复连接，避免重发未知结果的写请求。本人按原幂等键查事实 |
| 持久受理容量 | 默认200。持续接近200并出现已知`PURCHASE_QUEUE_FULL`说明过载，应减少入口流量；不要手动归零预算 |
| 超期非终态 | `deadline_at < UTC_TIMESTAMP(6)`且仍QUEUED/PROCESSING，连续两次扫描后仍存在则调查恢复任务、锁和数据库连接；数据库预算必须等于所有非终态数量 |
| Outbox | FAILED>0立即调查；最老PENDING/PROCESSING超过120秒需连同默认120秒受理截止和30秒发布租约判断。扫描所有状态，不能只看SENT延迟。正常负载最终应排空，持续增长先减流 |
| Rabbit | ready/unacked持续增长、`.dead`出现消息即调查；先核对原请求/订单/Outbox事实。死信不代表需新建订单，重投仍受原数据库幂等和截止约束 |
| Hikari | max默认10，pending持续>0或出现timeout即调查慢SQL、锁资源和连接持有；不先调大池。保存采样峰值与获取等待 |
| 库存/额度等审计 | 任意`violation_count>0`停止对应写入并人工核对，禁止裁剪至零或猜测修账 |
| 售后 | 定期在管理订单查询`MANUAL_REQUIRED`，保留支付、核销及审计历史；不提供自动改账接口 |
| 内存/GC/线程 | 观察连续负载后的堆、GC暂停、线程数与排空恢复；单点宕机会暂停受理/消费，持久事实恢复不等于服务高可用 |

上述120秒、30秒、200和10来自当前协议/配置，最终负载观测见阶段19报告；属于排障起点，不是生产SLA。指标只使用channel/outcome/resource等有限标签，不使用用户/订单/票档ID。SQL digest、锁快照只供内部人员，不能公开查询文本或账号数据。

## 密钥轮换与检查范围

新增版本密钥并指定当前版本，保留所有仍有有效电子票的旧密钥；验证旧票和新票后再安排旧密钥退役。不更改历史摘要，不生成固定默认密钥。完整凭证只在持票页/核销提交期间存在，禁止放URL、日志或浏览器存储。

`.github/workflows/ci.yml`只运行Java21普通测试/架构守卫及Node锁依赖和严格构建。真实MySQL/Rabbit/Redis、迁移及浏览器功能验证须使用独立环境和随机guard，配置见[功能验证](40-functional-verification.md)；远端CI是否实际运行通过，须以该次Actions结果为准，不能由本地构建推断。
