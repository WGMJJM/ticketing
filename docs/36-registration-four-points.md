# 注册模块四项定向升级

仅调整注册配套，保留Session、CSRF、BCrypt、用户名登录和Cookie语义；订单、抢票、候补、库存、目录缓存及电子票生产代码未改动。

## 调用链

发送：`AuthController.emailCode`校验邮箱、解析可信IP → `RegistrationEmailService.send` → 原`RedisRateLimiter.emailCode`多KEY限流 → Lua准备SENDING → 真实SMTP → 同一sendId激活ACTIVE。

注册：`AuthController.register`校验四字段 → `AccountService.register` → `RegistrationBloomFilter`分别预判用户名/邮箱，可能存在或未就绪时MySQL确认 → `RegistrationEmailService.consume`单段Lua消费 → 事务外BCrypt → MySQL短事务INSERT → 提交后追加Bloom。

原`uk_user_username`保留，新增`uk_user_email`。Bloom命中必须数据库确认，漏判、误判、故障及延迟追加不能绕过唯一索引。DuplicateKeyException在事务回滚后转409，不解析驱动错误字符串。验证码消费后数据库失败需重新领取，不引入跨存储补偿。

## Bloom初始化与恢复

保持单应用实例。启动时只撤销就绪标记、不清空位图；取MySQL最高ID作扫描上界，每页500条、每页一次Lua批量追加，最多扫描配置预期账号数。完成后才发布就绪标记；并发注册提交后也只追加位。旧账号的NULL邮箱跳过，用户名照常回填。

默认预期100万账号、目标误判率1%，按`m=ceil(-n*ln(p)/ln(2)^2)`与`k=round(m/n*ln(2))`计算，约959万位、7个偏移；用户名和邮箱使用独立Bitmap。几何参数写入Key前缀，变更参数不会覆盖旧布局。

缺标记、任一Bitmap丢失或读取失败时回到MySQL预查询。追加发现Bitmap缺失也撤销旧标记，不能因追加新用户而误认历史数据已完整；重启执行同一分页初始化，无新后台修复器。提交后写失败只记录错误类型，不能回滚账号；漏判由MySQL唯一索引兜底，不宣称Bloom强一致或未经测量的性能收益。

## 验证码与限流

安全随机6位数字，激活后300秒有效，最多错误5次。Redis保存用途/邮箱绑定的HMAC-SHA256摘要、发送标识、状态及次数；Key中的邮箱也经用途隔离的HMAC。不存验证码明文，不在响应或常规日志输出验证码、个人邮箱、SMTP凭据。

SENDING不可消费，也不可被另一发送覆盖。SMTP成功后由sendId激活；失败按同一sendId删除本次状态，清理故障则自然过期，旧发送不能删除后续验证码。重发准备即使旧码失效。Lua原子完成状态/次数/摘要校验、失败计次和成功删除，同码并发仅一次通过。

发送IP、邮箱冷却与24小时窗口沿用原`request-rate-limit.lua`，全部通过才一起递增；默认10次/600秒、1次/60秒、5次/86400秒，窗口从首次放行开始，并非自然日零点重置。Policy最大窗口扩展到86400秒。SMTP失败不返还限流额度；原注册POST IP限制仍保留。限流关闭或不可用时发送失败关闭。

## 真实邮件配置

填写私有环境变量，不提交真实值：

| 环境变量 | 含义/默认值 |
| --- | --- |
| `REGISTRATION_EMAIL_ENABLED` | 显式开启填true；默认false，发送/验证返回503 |
| `REGISTRATION_CODE_SECRET` | 独立随机Base64密钥，解码32至128字节，不复用限流或票券密钥 |
| `SMTP_HOST`、`SMTP_PORT` | 实际SMTP地址/端口，默认587 |
| `SMTP_USERNAME`、`SMTP_PASSWORD`、`SMTP_FROM` | SMTP身份、密码或授权码、合法发件邮箱 |
| `SMTP_AUTH`、`SMTP_STARTTLS_ENABLED`、`SMTP_SSL_ENABLED` | 默认true/true/false；465隐式TLS按供应商设置STARTTLS=false、SSL=true |
| `RATE_LIMIT_ENABLED`、`RATE_LIMIT_KEY_SECRET` | 保留原开启状态及独立HMAC限流密钥 |
| `REGISTRATION_NAMESPACE` | 默认default，各独立环境隔离命名空间 |
| `REGISTRATION_BLOOM_ENABLED`、`REGISTRATION_BLOOM_EXPECTED_ACCOUNTS`、`REGISTRATION_BLOOM_FALSE_POSITIVE_RATE` | 默认true、1000000、0.01 |
| `RATE_LIMIT_EMAIL_CODE_IP_LIMIT`、`RATE_LIMIT_EMAIL_CODE_IP_WINDOW_SECONDS`、`RATE_LIMIT_EMAIL_CODE_COOLDOWN_SECONDS`、`RATE_LIMIT_EMAIL_CODE_DAILY_LIMIT` | 默认10、600、60、5 |

SMTP连接、读取和写入超时各3秒，TLS检查服务器身份。未配置真实邮件不能返回假成功。前端发送15秒超时、忙碌态防重复，默认60秒冷却，429按Retry-After倒计时，卸载清理定时器。密码、邮箱和验证码不进入浏览器持久存储。

## V12与历史账号

新增`V12__registration_verified_email.sql`，V1～V11不变。新增可空邮箱、UTC验证时间、邮箱唯一索引及二者同时为空/非空的CHECK。公开注册只接受ASCII邮箱、去首尾空白并转小写，数据库`utf8mb4_0900_as_ci`大小写不敏感。旧用户、管理员、工作人员NULL邮箱合法，不补造验证事实，不要求先补邮箱才能登录。

`AdminCredentials`与公开`RegisterRequest`分离，显式初始化保留角色检查、不重置旧密码规则，无新增公开提权入口。非注册测试用历史无邮箱账号夹具，仅位于测试目录。

2026-10-26只读核对本机`ticketing-mysql-1`：V10、原用户名唯一索引存在、无邮箱字段。本轮未执行业务库DDL，V11/V12等待新的具体升级批准；先停写、备份、审计，不直接启动应用触发升级。DDL失败保留现场，不repair或清库。

## 本轮验证范围

- Java 21主代码和全部测试源码编译通过；既有`ArchitectureRulesTest`、`RateLimitConfigurationTest`共5项通过。
- 前端`npm run build`的TypeScript检查及生产构建通过。
- 仅在已有隔离Redis中用随机临时Key验证验证码并发单次消费、错误上限、重发/旧发送清理、Bloom就绪/丢失回退及原多KEY限流拒绝不部分扣额；临时Key已逐项删除。
- 未执行V12 DDL、数据库并发唯一性专项或真实SMTP收件联调；未跑全量测试、E2E、压测，编译不等同于这些通过。
- 旧管理员签名及非注册账号夹具仅作编译兼容。旧HTTP注册、E2E及压测脚本仍需下一专项阶段接入四字段及真实邮箱验证流程，本轮未改其断言制造通过。

当前不支持国际化邮箱或邮箱登录，保持单实例部署；实际收件仍需供应商配置与收件联调。
