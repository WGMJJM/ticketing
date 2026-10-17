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
7. 检查 `/about`、活动详情、场次选择及运营管理。演示活动须显式标记，无购票按钮。
8. `npm run build` 同时完成 TypeScript 检查和生产打包。

数据库测试位于 `DatabaseMigrationIT`，测试ID使用900001段，推荐独立开发库。`ActivityManagementIT`、`TicketManagementIT`覆盖原有业务；`AuthenticationIT`使用随机端口Tomcat、真实HTTP、Cookie、Session和MySQL完成认证及旧会话撤权，`SessionCookieIT`单独检查生产Secure属性。`CurrentAccountFilterTest`验证公开端点跳过账号查询及安全刷新Context。普通业务用例事务回滚；并发编辑与真实HTTP认证用例提交独立夹具并在结束后定向清理。当前30项基础测试加33项集成测试共63项，最新结果见 `docs/10-session-permission-verification.md`。不证明购票并发、跨用户场次限购或核销正确性。

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

当前数据库为V3。执行原有普通Maven构建与真实MySQL profile，新增销售状态单元测试、票档HTTP集成测试及并发编辑冲突测试；具体结果见 `docs/08-ticket-inventory-verification.md`。需要票档样例时，手动将 `scripts/demo-tickets.sql` 复制到容器并按上文相同方式导入，不会由Flyway自动导入。

用户在活动详情选择场次后查看票档；管理员在活动管理中打开“场次”，再进入“票档管理”。启用销售前需确认销售窗口；已启用时仅能修改总库存，下架后可改其他基本信息。表单输入人民币元，接口发送整数分字符串。状态以服务端查询结果为准，可点击刷新获取最新结果。

## Git 与本地文件

仅在 main 开发；先完成本地代码、测试和联调，再提交并推送到用户指定的 WGMJJM/ticketing。README.md 只保留在本地并被忽略，远程共享说明在 docs。`.env`、`.local`、构建产物均不得提交。

## 排障与停止

Compose 日志用 `logs --tail 80 <服务>`；后端启动失败优先检查 MySQL、密码与 Flyway 报错，不通过关闭迁移绕过。Redis 与 MQ 故障会影响聚合健康但系统状态仍可能可访问。日志与 JSON 不应输出明文凭据和内部堆栈给前端。

前后端开发终端用 Ctrl+C；容器用 Compose `stop`，保留数据。不要为修复密码或迁移问题直接删除数据卷。未应用的迁移可以在初次验证前修正；一旦 V1 已应用，所有后续数据库变更使用新版本。
