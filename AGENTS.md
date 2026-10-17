# 票务交易平台开发规范

- 项目中文名称为「票务交易平台」，英文技术名为 `ticketing`，默认包名为 `com.example.ticketing`。
- Java 21，遵循《阿里巴巴Java开发手册》；使用明确命名与必要的中文注释，DTO 优先使用 record。
- 保持前后端分离、模块化单体架构，后端只有 `backend/pom.xml` 一个 Maven 模块。不得擅自引入微服务或额外基础设施。
- 保留 `common/api`、`common/controller`、`config` 的分层；后续按 `auth/event/ticket/order/waitlist/checkin` 业务包逐步添加真实实现。
- 不创建空接口、空 Store、多余工具类、无意义抽象、魔法值、硬编码凭据或虚假业务。不泛滥使用无依据的防御分支。
- Flyway 已应用的迁移不可修改；数据库变更新增 V2/V3。不得迁移旧 `rrs` 项目代码和数据。
- MySQL 是交易与库存权威；库存预占、支付转售出、取消与超时释放必须在事务中使用状态及库存条件更新。
- Redis 仅用于明确的缓存、限流或削峰职责。RabbitMQ 异步事件必须设计 Outbox、确认、重试和幂等消费。
- Docker 优先复用已有兼容镜像。Compose 默认 `pull_policy: missing`，不要无故拉取或更新镜像；完全离线使用 `--pull never`。
- 数据库 DATETIME(6) 统一为 UTC；外部日期带明确时区，用户界面使用 `zh-CN` 和 `Asia/Shanghai`。金额为人民币分整数，展示为 `¥` 与两位小数；BIGINT ID 通过字符串传输。
- 用户可见文案统一简体中文；技术状态映射为中文，不泄漏内部异常或凭据。
- 每次编码后执行与变更相称的检查，并用中文报告实际测试结果和未能验证的内容；不得宣称未经测试的并发指标或业务能力。
- 用户指定直接在 `main` 开发和推送，不新建分支。远端为 `https://github.com/WGMJJM/ticketing.git`，不得推到其他项目或另建仓库。
- 每个独立功能验证通过后单独提交并立即推送；提交信息使用中文且不含半角或全角冒号。只暂存本模块文件，不提交 `.env`、日志或构建产物。
- 本仓库 Git 作者为已确认的 GitHub 账户 `WGMJJM`，邮箱 `2829827503@qq.com`；仅设置仓库本地配置。不得强推、覆盖远端提交或绕过保护规则。
- README 保留在本地，根目录 `/README.md` 必须被 `.gitignore` 忽略，不得提交或推送到远程；需要共享的开发与验收说明放在 `docs/`。

## 常用验证

普通后端测试：`mvn -f backend/pom.xml clean verify`，不依赖本地中间件。

真实 MySQL 验证：先启动 Compose，再执行 `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/start-backend.ps1 -VerifyDatabase`；该脚本导入 `.env` 并启用 `mysql-it` profile。

前端：`cd frontend` 后执行 `npm ci`、`npm run build`。构建包含严格 TypeScript 检查。不要把依赖安装当作构建完成。
