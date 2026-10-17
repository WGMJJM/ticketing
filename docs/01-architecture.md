# 架构与边界

前后端分离的模块化单体，一个 Maven 后端、一个 Vue 前端。Java 21、Spring Boot 3.5.16、MyBatis-Plus 3.5.17，沿用 common/api、common/controller、config 分层；活动与场次在 event 下按 controller/service/mapper/entity/dto/model 实现。

Java 根包统一为 `com.ticketing`，源码和测试分别位于 `backend/src/main/java/com/ticketing`、`backend/src/test/java/com/ticketing`。启动类 `com.ticketing.TicketingApplication` 位于根包，Spring Boot 组件扫描和 MyBatis Mapper 自动扫描覆盖现有 common、config、event 等子包；不重复配置扫描路径，不创建尚未实现的空业务包。

浏览器通过 Vite 代理访问 /api/v1；Spring Security 控制公开读取与管理员权限，Controller 校验请求，Service 执行业务与事务，MyBatis-Plus Mapper 读写 MySQL。实体不直接返回前端，响应 DTO 将 BIGINT ID 转为字符串，将数据库 UTC 时间转为 ISO 8601 Instant。

当前公开功能为活动分页筛选、已发布活动详情、场次列表及票档查询。管理功能包括活动发布下架、场次维护取消、票档启用下架和总库存调整。更新使用数据库版本与状态条件，避免陈旧页面覆盖新修改。每个活动最多200个场次，新增时锁定所属活动行检查上限；场次不检查时间重叠。

账号模块位于 `com.ticketing.auth`，复用user_account。UserDetailsService查询数据库，Spring Security标准用户名密码过滤器使用BCrypt验证并持久化SecurityContext到HttpSession；登录更换会话ID，退出销毁会话。AccountPrincipal包含可信用户ID和角色，认证后擦除凭据。所有认证及管理写请求保留CSRF，敏感响应禁止缓存。旧DevAdminConfig、Basic及admin/session入口已移除。

Vue仍为单工程，Pinia只保存用户展示信息，刷新通过auth/me恢复身份；密码仅在表单提交期间使用，提交后清空，不保存Basic或可复用登录密钥。浏览器携带HttpOnly、SameSite=Lax的会话Cookie；空闲30分钟失效，仅Cookie跟踪。Secure默认true，受控本地HTTP通过环境变量显式设false，生产HTTPS保持true。注册、登录、退出和管理写操作前获取当前CSRF，登录和退出后再次获取；不自动重放写请求。路由守卫辅助导航，最终授权以服务端为准。

管理员通过默认关闭的admin-bootstrap配置创建，不预置密码；已有管理员不会重置密码，已有普通或禁用账号不会自动提权。会话保存在单实例内存，重启失效。CurrentAccountFilter在/auth/me、订单及管理接口授权前按可信用户ID只读取账号身份、状态和角色；禁用或删除账号使会话失效并返回401，角色变化创建不含凭据的新Context并显式保存，再由现有权限规则授权，ADMIN被撤销后返回403。公开查询及认证端点跳过账号复查；新增受保护路由须加入SecurityConfig共用匹配范围。没有提供账号管理、密码找回或跨实例会话模块。

票档模块位于 `com.ticketing.ticket`，复用现有分层、认证与 CSRF。TicketService 统一维护销售窗口、可售状态、总库存和管理版本；与 SessionService 共享场次行锁维护跨表时间规则，未引入分布式锁。库存查询为 MySQL 事实计算，ON_SALE 管理状态不等同于正在售票。

订单模块位于 `com.ticketing.order`，复用ticket_order与唯一明细。一个READ_COMMITTED事务内校验真实销售状态、原子预占库存、保存服务端价格快照和PENDING订单。取消按PENDING条件转CANCELLED并释放锁定库存；限购通过相同用户行锁保护的有效订单统计同步释放，不维护重复配额字段。固定锁序为用户UPDATE→活动SHARE→场次SHARE→票档UPDATE→已有订单UPDATE。管理员操作按活动→场次→票档顺序，场次使用UPDATE锁，与下单共享父级协议；活动下架使用活动UPDATE锁。等待结束后使用当前读重新检查状态与版本，库存预占和释放均递增票档版本，避免旧管理页面覆盖新库存。

用户锁串行化同一用户的所有创建和取消，因此跨票档的场次限购与幂等重放均在同一互斥范围内，READ_COMMITTED统计能看到前一事务已提交的订单。不同用户共享父级读锁并在SKU条件更新处竞争；单用户多场购票也会串行，是第一版的明确吞吐取舍。未来支付和自动关单必须继续遵守用户→父级→SKU→订单的顺序。

Vue活动详情提供数量、限额、精确预计金额和真实确认下单；我的订单与详情只查询本人，Session与CSRF沿用现有机制。网络结果不明时当前页面保留原幂等键并冻结数量，用户可重试或查询订单；不自动重放写请求。服务器返回截止状态，页面不自行宣称超时关单或支付成功。

MySQL是库存与订单权威。Redis和RabbitMQ仅保留基础设施与健康检查，未接入认证、缓存或消息业务。本阶段没有支付、自动超时释放、候补、电子票、Outbox发布或核销；超过截止的PENDING订单仍占库存和限额，需主动取消。不承诺高并发抢票能力。
