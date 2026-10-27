# 独立功能验证入口

后端普通测试、MySQL/Redis/RabbitMQ集成测试、Playwright业务测试和业务审计SQL保留。清理旧负载工具时，将此前共用的功能验收代码提取到`scripts/verification/`；这里不生成负载，不计算性能结果，也不提供性能环境管理命令。

## 基础构建

```powershell
mvn -f backend/pom.xml test-compile -DskipTests
cd frontend
npm run build
```

需要执行普通后端测试时使用`mvn -f backend/pom.xml clean verify`。本次清理只做基础构建，没有执行完整集成或浏览器回归。

## 真实中间件功能验证

另行准备独立的MySQL、Redis和RabbitMQ，分别监听本机13307、16379、15673；浏览器验收应用使用18080，前端生产预览使用5174。它们不得映射到业务容器的数据卷，不能使用3307的`ticketing`业务库。本次清理没有创建这些资源。

现有验证器从被忽略的`.local/verification/environment.json`读取以下配置，不加载业务`.env`：

| 字段 | 含义 |
| --- | --- |
| `environment` | 固定为`ticketing-verification-only` |
| `guardToken` | 独立实例中随机生成的隔离标记 |
| `rootPassword` | 专用MySQL管理账号口令 |
| `dbPassword` | 既有迁移测试受限账号口令 |
| `mqPassword` | 独立RabbitMQ账号`ticketing_verify`的口令 |
| `adminPassword` | 独立应用`verify_admin`账号口令 |
| `ticketKeys` | 独立电子票签名密钥版本JSON字符串 |

专用MySQL需有空的`ticketing_verify`库，以及`performance_control.environment_guard(token)`中与`guardToken`一致的随机值。该guard表名和受限账号`ticketing_perf`是保留的Java迁移/恢复功能测试契约，不是退役压测目录的执行入口。受限账号仅在独立验收实例内使用，口令对应`dbPassword`；参考结构创建还需符合既有触发器授权要求。不要复制历史环境凭据或在业务实例创建测试guard。

现有Python依赖统一位于`scripts/requirements.txt`，仍仅为requests与PyMySQL。准备好独立资源后，可运行既有集成入口：

```powershell
python -B scripts/verification/verify_backend.py --label functional_run
```

验证器创建本轮随机schema，沿用原有成功后定向清理规则；失败或DDL失败保留现场。`scripts/verify-aftercare.py`保留为售后功能验证入口。`recover-v7.py`的备份验证目标容器须通过`RECOVERY_VERIFY_CONTAINER`显式给出，仍要求独立实例密码、随机guard和备份逐行核对；不自动恢复或迁移业务库。

## 浏览器与只读业务审计

`frontend/e2e/delivery.spec.mjs`、Playwright配置与脱敏报告器保留；夹具入口迁至`scripts/verification/browser_fixture.py`，私有夹具、应用进程记录和输出均使用`.local/verification/`。数据库一致性SQL为同目录的`audit.sql`、`audit-purchase.sql`、`audit-quota.sql`、`audit-buckets.sql`，同时继续供受控升级工具使用。

只有显式执行验证时才生成临时文件，验证完成后清理不再需要的夹具、日志、截图、JAR和结果，不在`.local`保存多轮历史。本次没有保留旧验证产物或另行归档。

浏览器夹具使用注册邮箱验证码。隔离配置还须包含随机 `registrationSecret`（32 字节 Base64）和空闲本地 `smtpPort`；先在独立环境启动 `python -B scripts/verification/mail_sink.py --port <smtpPort>`，该测试收件箱只监听 127.0.0.1，验证码只写入忽略目录，不发送外部邮件。随后运行 `python -B scripts/verification/browser_fixture.py --jar backend/target/ticketing-backend-0.1.0-SNAPSHOT.jar --action prepare`。在 `frontend/` 设置唯一 `TICKETING_E2E_LABEL` 并执行 `npm run e2e`；完成后运行 `python -B scripts/verification/browser_fixture.py --action audit --label <唯一标签>`。全部服务均使用隔离端口和 guard，测试结束停止隔离进程并清理临时凭据。

业务部署健康检查仍使用`scripts/check-deployment.py --base-url <来源地址> --output <本地报告>`；旧负载环境专用选项已移除。加载受保护管理诊断配置时，沿用现有管理员会话与网络边界。
