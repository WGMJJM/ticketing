# 第十二阶段Redis请求频率保护验收

本文保留第十二阶段的历史验收口径。第十三阶段已增加新会话预算和显式受信代理，并完成原开发库受控V7恢复；当前部署与验证说明见[阶段十三验收](23-session-proxy-migration-verification.md)。

## 范围与接入

Spring Security执行顺序为：`CsrfFilter` → 认证入口限流 → 现有Logout/formLogin → `CurrentAccountFilter` → `AuthorizationFilter` → 业务提交限流 → Controller。登录由真实`UsernamePasswordAuthenticationFilter`处理，限流读取同一表单参数，注册分支不读JSON Body。两个过滤器实例使用不同的already-filtered属性，只由Security链注册，无Servlet重复注册。限流不打开数据库事务，拒绝后不进入交易服务。

登录采用全部**放行尝试**计数：密码正确、错误、账号不存在或停用的认证尝试都消耗相同预算；成功不清零。达到任一维度的阈值即拒绝，拒绝不递增任何维度、不延期现有窗口。只有有效CSRF请求进入计数，业务提交还必须先通过最新数据库身份与授权。Controller参数校验错误仍消耗已放行预算；这防止无效提交无限制进入应用层。

## Redis键和窗口

键为`ticketing:rate-limit:v1:<namespace>:<category>:<HMAC-SHA256十六进制摘要>`，分类为login-ip、login-account-ip、register-ip、order-user、waitlist-user。输入按分类以NUL分隔，账号trim后按Locale.ROOT小写；用户名原规范只允许ASCII。登录账号维度与连接IP绑定，无独立账号全局封禁键。HMAC使用独立环境密钥，防止对低熵用户名/IP直接进行无密钥字典匹配；多实例共享密钥、命名空间与Redis实例才能共享预算。变更这些配置重置短期预算，数据库业务事实不变。

Lua在Redis单次原子执行中先检查所有维度，再一起INCR，并仅在首次计数时设置PEXPIRE。窗口从该维度首笔放行开始，使用Redis自身PTTL；不依赖应用主机时钟。不同登录维度各有窗口起点，拒绝返回所有当前受限维度的最大剩余TTL，毫秒向上取整为秒。拒绝不延长窗口，误写的永久计数键会恢复配置TTL，避免永久封禁。当前部署为单机Redis，未宣称支持Redis Cluster多槽Lua。

这是固定窗口，窗口边界附近可出现接近两倍阈值的短时突发，不宣称严格任意滑动时间段内限额。默认IP登录60/60秒、账号+IP登录10/60秒、注册IP 10/600秒、下单用户30/10秒、候补用户10/60秒。下单同键重试照常消耗预算；限流不缓存成功结果、不绕过MySQL幂等。30/10秒允许常规重试，恶意反复使用同键也受保护；耗尽后保留原订单及请求键，待窗口恢复后仍返回原订单。

连接地址仅取`request.getRemoteAddr()`，明确关闭Spring转发头处理。不信任客户端X-Forwarded-For、X-Real-IP和Forwarded；当前没有受信代理列表功能。反向代理部署会按代理连接IP共享预算，应评估并配置阈值，不能直接信任互联网请求头。

## 故障、隐私与前端

Redis连接/命令超时默认500ms，受限入口故障关闭，统一503 `RATE_LIMIT_UNAVAILABLE`、中文信息、Retry-After默认1秒及no-store，不无限制放行。首次故障只记录分类和异常类型，恢复只记录一次；不打印异常正文、请求体、键、Session或凭证。指标`ticketing.security.rate.limit.requests`只有四个route和三个outcome标签，不使用用户、订单、IP或Redis键作为标签。

故障仅影响四类提交；公开活动/场次/票档读取、已登录查询、支付、取消、退款、核销及后台补偿不新增Redis依赖。保持已有BCrypt、30分钟Cookie Session、HttpOnly/SameSite/Secure策略、Session fixation防护和CSRF刷新。前端统一处理429及该503的Retry-After，登录/注册/下单/候补展示中文等待提示，不自动重试。首次提交明确的限流503不再提示订单结果未知；真正网络中断/其他5xx保留同键确认行为。若先前提交已超时待确认，后续429/限流503仍保留原幂等键与待确认状态，不能因为本次拒绝而丢失上一笔提交的事实。

## 测试与复现

普通测试资源显式关闭限流，避免基础/历史业务回归隐式依赖Redis；生产配置默认开启且缺密钥启动失败。`RateLimitConfigurationTest`验证默认开启、必要密钥、边界、部分策略绑定及受控关闭。`RateLimitIT`显式启用真实Redis，使用随机it_命名空间和密钥，只SCAN/DELETE自身键；不FLUSHDB、不清理开发Redis。

真实集成须先启动专用Compose，在独立`_it`数据库配置MYSQL_*与RABBITMQ_*，执行`scripts/start-backend.ps1 -VerifyDatabase`。Redis默认16379，可用RATE_LIMIT_TEST_REDIS_PORT覆盖。测试覆盖真实formLogin正确/错误计数、Session换号及退出、CSRF先于限流、未知/停用账号统一错误、IP组合公平性、大小写、伪造代理头、TTL恢复、两实例160并发精确放行50、业务无写拒绝及窗口后幂等、STAFF撤权/停用先于业务保护、503响应与真实断连超时。HTTP 503细分入口的单测使用Redis客户端异常注入；另外的专用环境探针实际停止Redis，不能将注入测试当作真实停机证据。

生产阈值探针必须在新启动的独立进程中执行，避免旧请求消耗预算；包含真实160个HTTP注册请求（有效CSRF、无效Body不写入账号），精确10次到Controller返回400、150次429，下单/候补预算、真实专用Redis停机四入口503、公开/Session查询保持200、事实不变及重启恢复。禁止对开发或真实业务Redis执行停机探针。

## 部署边界

第十一阶段原开发库的V7失败历史和部分DDL仍保留，未repair、清库或改写迁移。部署前只读执行audit-refund-readiness.sql、audit-waitlist-relations.sql、audit-staff-audit-readiness.sql，出现异常/STOP必须停止部署；隔离测试通过不表示原失败库已恢复。本阶段不要求更改库结构。

## 本轮结果

验收日期为2026-10-23。先完成代码和整轮验证，再统一提交main。后端生产代码完成全量测试后未再修改；前端补充待确认请求的同键保留后重新构建并完成真实超时联调。

| 检查 | 实际结果 |
| --- | --- |
| `mvn -f backend/pom.xml clean verify` | 58项基础测试，失败0、错误0、跳过0，编译与打包成功 |
| `scripts/start-backend.ps1 -VerifyDatabase` | 专用MySQL/RabbitMQ/Redis及新建ticketing_stage12_final_it中58项基础加161项真实集成，共219项全部通过，失败/错误/跳过均为0 |
| `RateLimitIT` | 11项全部通过；两实例160并发精确放行50、拒绝110；真实HTTP注册TTL恢复、登录链路、代理头、角色/CSRF顺序、订单/候补无写拒绝及幂等恢复通过 |
| 生产阈值真实HTTP并发 | 160次注册，10次通过限流到Controller校验返回400，150次429；下单30次、候补10次预算后429，数据库事实不变 |
| 真实Redis停机/恢复 | 四类入口均503，实测500/515/532/515毫秒；公开和Session订单读取200，事实不变，重启恢复 |
| 历史交易及权限回归 | 全量包含退款/取消善后、核销/工作人员授权、迁移、Outbox确认/重试及数据库补偿测试，未跳过原测试 |
| `npm ci` | 安装143个包，npm报告0个漏洞 |
| 最终`npm run build` | vue-tsc严格检查与Vite生产构建通过 |
| 性能工程安全测试 | 7项全部通过，含环境标记、固定Compose、本机Docker、报告目录、参数和秘密隔离 |
| 共享结果/迁移检查 | setup_data、Cookie、密码、有效票券、签名及限流密钥扫描通过，V1～V7无修改 |

### 浏览器联调

真实Vue页面使用独立ticketing_stage12_browser库和独立Redis命名空间，测试时将登录账号+IP阈值设3、注册IP设2、下单用户设2、候补用户设1，注册、下单与候补窗口均设60秒，便于观察；这些是显式测试参数。登录/注册达到阈值均显示中文等待秒数；购票和候补先各成功创建一笔，再次高频提交显示429。实际停止专用Redis后购票显示503，前后订单仍1笔、候补仍1笔、locked仍1、sold仍0。

![登录达到阈值后的中文提示](images/stage12-login-rate-limit.png)

![超时待确认的订单在Redis故障后仍保留原请求重试](images/stage12-uncertain-retry.png)
