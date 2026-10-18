# 第一阶段实际验收记录

验收日期：2026-10-17，Asia/Shanghai；工作目录 `D:\ticketing`。本记录只描述实际运行结果，不表示购票业务已经实现。

## 环境与镜像

- Java 21.0.10、Maven 3.9.9、Node 25.9.0、npm 11.12.1。
- Docker Engine 29.5.2、Docker Compose 5.1.3，Linux 容器环境。
- 复用已有 `mysql:8.4`、`redis:7-alpine`、`rabbitmq:4.1-management-alpine`；启动命令使用 `--pull never`，未拉取镜像。未读取或复用其他项目代码、业务数据与数据库。
- `.env` 已生成随机本地开发密码，未写入示例配置或文档。MySQL、Redis 和 MQ 使用本项目新建命名卷。

## 命令与结果

| 实际命令或检查 | 结果 |
| --- | --- |
| `docker compose --env-file .env -f deploy/compose.yml config --quiet` | 通过 |
| `docker compose --env-file .env -f deploy/compose.yml up -d --pull never --wait --wait-timeout 180` | 三个容器全部健康，复用本地镜像 |
| `mvn -f backend/pom.xml clean verify -B -ntp` | 构建成功，4 项单元/MVC 测试通过 |
| `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/start-backend.ps1 -VerifyDatabase` | 最终构建成功，4 项基础测试及 5 项 MySQL 集成测试全部通过 |
| Flyway 首次连接空 `ticketing` 库 | V1 成功执行，十张领域表及历史表建立；再次运行校验成功 |
| `npm install --no-fund --no-audit` | 依赖安装完成，生成 lockfile |
| `npm run build` | 严格 TypeScript 检查与 Vite 生产打包通过 |
| Git Bash `bash -n scripts/start-backend.sh` | 语法检查通过 |
| GET 后端 `/api/v1/system/status` | 200，中文应用名和统一响应正确 |
| GET `/actuator/health` | 200，`UP`，真实连接 MySQL、Redis、RabbitMQ |
| GET Vite `/api/v1/system/status` | 代理成功，真实后端响应 |
| GET `/api/v1/orders`、`/login`、`/actuator/env` | 均为 403；没有公开默认登录页 |
| 后端实际启动日志 | 未生成默认 Spring Security 随机密码 |

## 浏览器验收

通过内置浏览器访问本地 Vite：首页为中文，类别、初始化阶段说明和空状态正确；`/about` 导航可用。桌面视图及 390×844 的同源 iframe 手机布局已目视检查，手机导航、主视觉和内容正常排列。没有使用第三方海报或虚构可售活动。

真实停止本项目 Java 后端后，首页显示「暂时无法连接后端服务」，内容仍能浏览。重新启动后端并等待就绪，点击「重新检测」恢复「服务运行正常」，请求期间显示「正在连接服务」。临时手机验收页面已移除。

桌面与手机截图保存在被忽略的 `.local/home-preview.png`、`.local/mobile-preview.png`。本次为方便预览保留本地前后端后台进程及三个容器；进程 ID 记录在 `.local/backend-pid.txt`、`.local/frontend-pid.txt`，日志也在 `.local`。再次运行启动命令前，应先停止当前预览进程以避免端口冲突。

## 验证中修正的问题与限制

Windows PowerShell 5.1 将无 BOM 的中文脚本按系统代码页解析，已将 PS1 改为 UTF-8 BOM，并实际运行成功。MySQL CHECK 违规返回 HY000/3819，Spring 不一定映射为 DataIntegrityViolationException；测试改为核对底层 MySQL 错误码，未放宽数据库约束。

Spring Boot BOM 管理的 Flyway 11.7.2 在 MySQL 8.4 上输出兼容版本提示；本机实际空库迁移、校验及约束测试均已成功。保留 BOM 版本，未通过关闭迁移或隐藏警告绕过。MyBatis 未发现业务 Mapper 的提示符合当前未开发业务的阶段。

没有在原生 macOS/Linux 主机运行全套启动，只完成 Bash 语法检查；手机为浏览器内 390 像素布局检查，未测试实体设备。未实现也未验证登录、锁票、支付、候补分配、电子票核销、故障恢复或并发吞吐量，不声明 TPS/QPS/P99、零超卖或抢票性能。

## 后续阶段记录

上文保留第一阶段当时的验收事实，不表示后续模块仍未开发。2026年10月18日完成第八阶段性能及系统验收，详细命令、原始结果和限制见[第八阶段验收](15-performance-verification.md)、[压测复现](performance/01-reproduce.md)和[性能分析](performance/02-analysis.md)。

本轮普通Maven验证45项通过；现有VerifyDatabase脚本在独立MySQL/RabbitMQ环境执行45项基础加108项真实集成，共153项通过，失败/错误/跳过均0。npm ci及严格TypeScript/Vite构建通过。52组k6实测均通过MySQL快照审计，另有10类故障恢复、4项可检出的审计反例和5项隔离边界测试。浏览器实际完成注册登录、刷新恢复、越权拒绝、下单取消、模拟支付出票、候补分配支付和管理员核销及重复拒绝。

本阶段不改库存锁序、Session或签名方案。Outbox发布批量的对照数据、未采用的连接池调参和热点SKU瓶颈均如实保留；无生产容量、长时间稳定性或真实支付能力承诺。
