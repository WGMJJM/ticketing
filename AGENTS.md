# 票务交易平台开发规范

- 项目中文名称为「票务交易平台」，英文技术名为 `ticketing`，Java 根包统一为 `com.ticketing`。源码位于 `backend/src/main/java/com/ticketing`，测试位于 `backend/src/test/java/com/ticketing`，后续新增代码保持一致。
- Java 21，遵循《阿里巴巴Java开发手册》；使用明确命名与必要的中文注释，DTO 优先使用 record。
- 保持前后端分离、模块化单体架构，后端只有 `backend/pom.xml` 一个 Maven 模块。不得擅自引入微服务或额外基础设施。
- 保留 `common/api`、`common/controller`、`config` 的分层；后续按 `auth/event/ticket/order/waitlist/checkin` 业务包逐步添加真实实现。
- 不创建空接口、空 Store、多余工具类、无意义抽象、魔法值、硬编码凭据或虚假业务。不泛滥使用无依据的防御分支。
- Flyway 已应用的迁移不可修改；当前V1至V3保持不变，后续必要变更新增V4及更高版本。不得迁移旧 `rrs` 项目代码和数据。
- 账号统一使用数据库user_account与Spring Security Session，密码只存BCrypt。禁止恢复Basic、引入JWT双轨或在前端持久化凭据；写请求统一CSRF，登录和退出后重新获取令牌。后续业务从AccountPrincipal获取可信用户ID，不接受前端身份声明。管理员仅通过显式初始化创建，不允许公开注册提权。
- 需要身份的端点须在授权前按用户ID检查数据库最新状态和角色，禁用账号使会话失效，角色变化刷新会话身份。新增受保护端点必须加入SecurityConfig共用的RequestMatcher；公开查询不得额外查询账号。
- MySQL 是交易与库存权威；库存预占、支付转售出、取消与超时释放必须在事务中使用状态及库存条件更新。
- 场次开售时间是票档最早开售时间，票档停售不得晚于场次开始。票档管理和场次时间修改必须共享场次行锁并验证窗口；已启用销售的票档只可调整总库存。管理 API 禁止写入锁定、已售和可售库存，销售状态统一由后端计算。
- Redis 仅用于明确的缓存、限流或削峰职责。RabbitMQ 异步事件必须设计 Outbox、确认、重试和幂等消费。
- Docker 优先复用已有兼容镜像。Compose 默认 `pull_policy: missing`，不要无故拉取或更新镜像；完全离线使用 `--pull never`。
- 数据库 DATETIME(6) 统一为 UTC；外部日期带明确时区，用户界面使用 `zh-CN` 和 `Asia/Shanghai`。金额为人民币分整数，展示为 `¥` 与两位小数；BIGINT ID 通过字符串传输。
- 用户可见文案统一简体中文；技术状态映射为中文，不泄漏内部异常或凭据。
- 每次编码后执行与变更相称的检查，并用中文报告实际测试结果和未能验证的内容；不得宣称未经测试的并发指标或业务能力。
- 用户指定直接在 `main` 开发和推送，不新建分支。远端为 `https://github.com/WGMJJM/ticketing.git`，不得推到其他项目或另建仓库。
- 按用户最新要求先完善本地代码并完成整体测试与联调，再推送 `main`，不在未完成阶段逐模块推送。提交信息使用中文且不含半角或全角冒号。只暂存本次功能文件，不提交 `.env`、日志或构建产物。
- 本仓库 Git 作者为已确认的 GitHub 账户 `WGMJJM`，邮箱 `2829827503@qq.com`；仅设置仓库本地配置。不得强推、覆盖远端提交或绕过保护规则。
- README 保留在本地，根目录 `/README.md` 必须被 `.gitignore` 忽略，不得提交或推送到远程；需要共享的开发与验收说明放在 `docs/`。

## 常用验证

普通后端测试：`mvn -f backend/pom.xml clean verify`，不依赖本地中间件。

真实 MySQL 验证：先启动 Compose，再执行 `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/start-backend.ps1 -VerifyDatabase`；该脚本导入 `.env` 并启用 `mysql-it` profile。

前端：`cd frontend` 后执行 `npm ci`、`npm run build`。构建包含严格 TypeScript 检查。不要把依赖安装当作构建完成。
