# 旧会话账号状态与权限复查验收

验收日期：2026-10-17。基于main的e7c7f2e修复第三阶段旧会话权限快照问题。本轮仅完善认证，不开发订单、支付或其他业务。

## 最小实现范围

- 新增auth/security/CurrentAccountFilter，仅在GET /api/v1/auth/me及/api/v1/admin/**授权前执行。过滤范围与SecurityConfig权限规则共用RequestMatcher；以后新增受保护业务端点必须并入该范围。
- 对已认证身份使用AccountPrincipal中的可信用户ID，按主键读取当前id、username、status、role，不读取密码哈希。
- 账号禁用、删除或认证主体不符合数据库账号身份时，清除SecurityContext、销毁Session、清除JSESSIONID Cookie，返回401及ACCOUNT_UNAVAILABLE中文错误。之后恢复数据库状态也无法恢复旧会话，必须重新登录。
- 角色或用户名变化时创建无密码凭据的新身份和Context，显式保存到原SecurityContextRepository。现有授权规则使用新角色；ADMIN撤销为USER后管理接口403，auth/me显示USER。数据库受控重新授予ADMIN后按最新角色授权。
- 不原地修改并发请求共享的旧Context。账号变化在提交后的下一次有效受保护请求检查时生效，不追溯取消已经通过检查或已经执行的操作。
- 公开活动、场次、票档以及认证端点不增加此项账号复查；匿名请求无需查询账号。登录仍由原UserDetailsService正常读取账号。CSRF过滤保持原顺序，缺少或无效令牌的写操作仍先返回403。
- 原Session、Cookie、BCrypt和CSRF方案保持不变，不增加JWT、Redis Session、Maven模块、数据库字段或迁移。

已检查AccountService、AccountUserDetailsService、AuthController及frontend/src/stores/auth.ts。注册与登录无需改动；前端已有401清理展示身份、403重新获取auth/me的处理，与本次后端修复配合正常，因此未修改前端源码。

## 自动验证真实结果

| 命令或检查 | 结果 |
| --- | --- |
| mvn -f backend/pom.xml clean verify | 成功，30项基础测试，0失败、0错误、0跳过 |
| powershell -NoProfile -ExecutionPolicy Bypass -File scripts/start-backend.ps1 -VerifyDatabase | 成功，30项基础测试加33项真实MySQL集成测试，共63项，0失败、0错误、0跳过 |
| frontend下npm ci --no-fund --no-audit | 成功 |
| frontend下npm run build | 成功，包含vue-tsc严格类型检查与Vite生产构建 |
| 新版JAR启动与actuator/health | 正常启动，UP；Flyway校验V1至V3，无迁移变更 |
| git diff --check与旧包搜索 | 通过；未变更原远端、README忽略规则或本地凭据 |

基础测试组成：原19项加CurrentAccountFilterTest 11项。集成测试组成：AuthenticationIT 13项、ActivityManagementIT 6项、TicketManagementIT 8项、DatabaseMigrationIT 5项、SessionCookieIT 1项。

真实认证集成测试连接随机端口Tomcat，使用HttpClient、CookieManager、真实MySQL账号及CSRF，不用模拟身份来证明会话撤权。

新增真实HTTP用例断言：

1. 普通用户登录后被禁用，公开活动查询仍200，auth/me返回401并使会话失效；恢复账号后旧Cookie仍401，重新登录成功。
2. 管理员登录后被禁用，不先调用me而直接带有效CSRF写活动接口，返回401且未新增活动；恢复状态后旧Cookie仍不能访问后台。
3. 管理员登录、创建活动及票档后数据库撤销ADMIN，同一Cookie直接发布活动与票档均403；状态和票档版本未改变，me显示USER。受控恢复ADMIN后同一会话可正常操作，CSRF仍有效。
4. 账号被删除后已有会话不能继续读取身份。

新增单元测试覆盖8组公开/认证端点在携带旧身份时仍完全不调用账号Mapper；匿名受保护请求不查询账号；非数据库认证主体拒绝；角色刷新显式保存新Context、不携带密码且不修改旧Context。

原活动和票档MockMvc用例的身份夹具同步改为实际数据库账号，避免新检查被测试桩绕过。一般夹具仍随事务回滚，并发编辑用例按确切ID清理已提交的管理员夹具。原注册、BCrypt、登录失败、Session固定攻击防护、退出失效、Cookie安全属性、权限和CSRF以及所有业务回归用例均通过。

Docker继续复用既有MySQL 8.4、Redis 7和RabbitMQ 4.1镜像。Flyway既有MySQL 8.4兼容性提示仍存在，实际迁移校验和集成测试成功；本轮未升级依赖。

## 浏览器联调

使用独立本地验收账号和真实后端，未修改正式管理员账号或业务库存：

- 以ADMIN角色登录，确认原运营活动列表正常加载。
- 数据库撤销该验收账号ADMIN后，在未刷新旧页面的情况下点击筛选；后台拒绝访问，前端自动重新读取身份并转到个人中心，展示普通用户和无运营权限提示，管理员导航消失。
- 禁用同一账号后刷新个人中心，跳转用户登录，旧展示身份清除。
- 将验收账号恢复原USER/ACTIVE状态后重新登录成功，原活动详情、场次选择和公开票档查询正常。
- 夹具角色及状态已恢复，截图位于被忽略的.local/session-role-revoked.png，未推送凭据、截图或日志。

## 修改与边界

生产代码只修改SecurityConfig、AccountPrincipal并新增CurrentAccountFilter；测试和对应规范文档随之更新。前端源码和V1至V3均未修改。相关规则已同步到AGENTS、架构、API契约及本地开发说明；09号文档保留首次验收结果并链接本次修复。

仍为单实例内存Session，后端重启需要重新登录。本轮不新增账号管理、密码修改、订单或跨实例认证能力。按现有main、原origin及WGMJJM / 2829827503@qq.com提交，完成全部本地验证后推送，不强制推送。
