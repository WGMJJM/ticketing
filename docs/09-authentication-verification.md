# 第三阶段用户认证与权限管理验收

验收日期：2026-10-17。基于原main完成统一数据库账号体系，采用Spring Security Session与Cookie，保持单Maven模块及原Vue工程，不引入JWT或Redis会话。

本文件记录第三阶段首次验收；后续旧会话撤权修复及最新测试结果见 `docs/10-session-permission-verification.md`。

## 实际交付

- 普通用户注册：用户名格式校验、大小写不敏感唯一、BCrypt密码存储，固定创建USER/ACTIVE。未知字段（包括角色、状态、用户ID）被拒绝。
- 统一登录：数据库UserDetailsService与Spring Security标准表单过滤器，真实保存认证会话；错误密码、未知用户名和禁用账号拒绝登录。AccountPrincipal携带可信数据库ID，认证后擦除密码凭据。
- 会话管理：登录更换Session ID，退出销毁Session并清除Cookie。Cookie为HttpOnly、SameSite=Lax，空闲30分钟，仅Cookie跟踪；Secure默认true，本地HTTP显式false。
- 权限：auth/me只返回当前身份；未登录受保护接口401，普通用户管理接口403，所有写操作保留CSRF。认证响应禁止缓存，不返回密码、哈希或Session ID。
- 前端：注册、统一用户/管理员登录、个人中心、条件导航；Pinia仅保存展示身份，初始化和刷新从auth/me恢复。每次写操作前及登录退出后重新获取CSRF，无Basic或浏览器持久化凭据。
- 管理员初始化：默认关闭的ADMIN_BOOTSTRAP配置，必须显式提供合法用户名及随机强密码；已有管理员不重置密码，已有普通或禁用账号拒绝自动提权。旧DevAdminConfig、AdminSessionAccessController、admin Store和AdminLoginView均已删除，登录页共用LoginView。
- 数据库直接复用user_account，无必要新增字段，因此未创建V4，V1至V3保持原样。

## 自动验证结果

所有下列命令均已实际完成，最终运行结果为成功，不使用H2或仅用模拟身份证明认证。

| 验证 | 实际结果 |
| --- | --- |
| mvn -f backend/pom.xml clean verify | 成功，19项基础测试，零失败、零错误、零跳过 |
| powershell -NoProfile -ExecutionPolicy Bypass -File scripts/start-backend.ps1 -VerifyDatabase | 成功，19项基础测试加29项真实MySQL集成测试，共48项，零失败、零错误、零跳过 |
| frontend下npm ci --no-fund --no-audit | 成功，沿用现有锁文件 |
| npm run build | 成功，vue-tsc严格类型检查及Vite生产构建通过 |
| 后端启动与健康检查 | 新版JAR正常启动，actuator/health为UP，Flyway校验3个既有迁移 |
| 静态检查 | 差异空白检查通过，源码无旧Basic凭据拼接、持久化密码或旧根包引用 |

测试组成：原基础19项；ActivityManagementIT 6项、DatabaseMigrationIT 5项、TicketManagementIT 8项；新增AuthenticationIT 9项、SessionCookieIT 1项。

AuthenticationIT连接随机端口的真实Tomcat，使用Java HttpClient与CookieManager发送网络请求，账号及哈希落在真实MySQL。没有使用user()或WithMockUser建立认证测试身份；原业务MockMvc测试保留，并由真实管理员HTTP用例补充整个活动、场次、票档的管理链路。认证夹具按确切ID清理，不删除用户现有数据。

| 验收点 | 真实断言 |
| --- | --- |
| 注册与唯一约束 | 成功创建USER/ACTIVE、大小写冲突409、两客户端并发同名注册仅一个成功 |
| 密码安全 | 数据库hash不同于明文，符合BCrypt且PasswordEncoder验证通过；响应不包含密码/哈希 |
| 输入与越权注入 | 非法用户名、短密码、超过72字节、控制字符、额外role/status/id等字段400 |
| 登录失败 | 错误密码、未知账号、超长密码401；禁用账号401，均不能读取当前身份 |
| Cookie会话 | 登录后Cookie变化，后续多个请求恢复同一用户；旧匿名Cookie不能取得身份 |
| 退出 | 旧CSRF不能退出，新CSRF退出成功；旧已登录Cookie无法继续读取身份或访问后台 |
| 身份与权限 | 伪造userId参数仍只返回本人；匿名401、USER后台403、Basic头不能认证 |
| CSRF | 注册、登录、退出、管理员写操作缺失/错误令牌403；登录后旧令牌失效 |
| 管理端回归 | 真实ADMIN Session创建发布活动、创建场次、票档上架、库存更新与下架、取消场次、活动下架 |
| 管理员初始化 | 已有USER不提权；已有ADMIN不重置密码 |
| Cookie属性 | 本地HTTP配置有HttpOnly和SameSite；独立Secure配置实测响应包含Secure、HttpOnly、SameSite=Lax |

Secure测试是在本地HTTP服务器读取设置后的响应头，未部署生产TLS站点。Docker复用原MySQL 8.4、Redis 7、RabbitMQ 4.1镜像，未新增镜像或中间件。Flyway既有MySQL 8.4兼容性提示仍存在，实际校验与所有集成测试通过。

## 浏览器真实联调

通过127.0.0.1:5173的Vite代理连接真实后端与数据库，未模拟API。

1. 注册随机普通账号，页面跳转登录并显示中文注册成功提示；大小写变化后重复注册显示“用户名已被使用，请更换用户名”；错误密码显示“用户名或密码不正确”。
2. 正确密码登录进入个人中心，显示字符串用户ID和普通用户身份，导航不含运营管理；刷新页面后通过服务端恢复同一身份。
3. 普通用户直接打开/admin/activities被重定向个人中心并提示无权限；同Cookie直接访问管理API返回ACCESS_DENIED。
4. 点击退出后再次打开/profile，跳转登录页；导航恢复“登录 / 注册”。
5. 使用显式初始化的数据库管理员从/admin/login登录，同一Session机制进入原运营页面；刷新后保持管理员身份。
6. 原活动与场次列表正常，进入场次票档管理，将演示标准票库存100改为101保存成功，再改回100；锁定、已售库存保持0。
7. 原活动详情、场次选择、公开票档价格和销售状态回归正常；管理员退出后仍可公开浏览。
8. 后端未就绪时前端出现中文登录状态恢复错误；服务恢复后点击重试可继续注册与登录。

验收账号随机密码仅保存在被忽略的本地文件，未进入Git或截图。截图为.local/auth-profile.png与.local/auth-admin.png；日志、截图、账号夹具信息与构建产物均不推送。

## 核心文件与范围

后端核心：auth/controller/AuthController.java、auth/service/AccountService.java、auth/service/AccountUserDetailsService.java、auth/security/AccountPrincipal.java、auth/AdminBootstrap.java及现有config/SecurityConfig.java、application.yml。

前端核心：stores/auth.ts、api/auth.ts、views/LoginView.vue、RegisterView.vue、ProfileView.vue、router/index.ts及App.vue；原admin/tickets API统一改用Session请求。API契约、数据库说明、架构、开发计划、本地开发说明及AGENTS已更新。

会话当前保存在单实例内存，后端重启需重新登录。首次验收时的旧会话权限问题已在后续修复：受保护请求复查数据库，禁用账号使会话失效，角色变化刷新会话身份，详见10号验收记录。密码找回、修改密码、账号管理、跨实例会话均不在本轮范围。没有开发订单、支付、候补、抢票、库存预占或空占位模块，也未声称高并发交易能力。

使用现有main与原origin，作者WGMJJM，邮箱2829827503@qq.com，先完成本地验证与联调再推送；README继续只在本地保留。
