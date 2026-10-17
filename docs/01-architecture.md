# 架构与边界

前后端分离的模块化单体，一个 Maven 后端、一个 Vue 前端。Java 21、Spring Boot 3.5.16、MyBatis-Plus 3.5.17，沿用 common/api、common/controller、config 分层；活动与场次在 event 下按 controller/service/mapper/entity/dto/model 实现。

Java 根包统一为 `com.ticketing`，源码和测试分别位于 `backend/src/main/java/com/ticketing`、`backend/src/test/java/com/ticketing`。启动类 `com.ticketing.TicketingApplication` 位于根包，Spring Boot 组件扫描和 MyBatis Mapper 自动扫描覆盖现有 common、config、event 等子包；不重复配置扫描路径，不创建尚未实现的空业务包。

浏览器通过 Vite 代理访问 /api/v1；Spring Security 控制公开读取与管理员权限，Controller 校验请求，Service 执行业务与事务，MyBatis-Plus Mapper 读写 MySQL。实体不直接返回前端，响应 DTO 将 BIGINT ID 转为字符串，将数据库 UTC 时间转为 ISO 8601 Instant。

当前公开功能为活动分页筛选、已发布活动详情和场次列表。管理功能包括创建与编辑活动、发布与下架、创建与编辑场次和取消场次。更新使用数据库版本与状态条件，避免陈旧页面覆盖新修改。每个活动最多200个场次，新增时锁定所属活动行检查上限；场次不检查时间重叠。

开发管理员仅在 DEV_ADMIN_ENABLED=true 时启用，从环境变量读取用户名和密码，内存中保存带 BCrypt 标识的哈希。每次管理请求必须提供 Basic 凭据，写操作还需要 CSRF 令牌；GET /admin/session 认证后返回令牌并禁止缓存。服务绑定 127.0.0.1，该机制只用于受控开发联调，后续由正式用户认证替换。默认关闭时，管理接口仍受保护。

Vue 仍为单工程，Pinia 管理实际的开发管理员会话状态。凭据仅在内存中保存，不写入前端构建、浏览器持久存储或 Git；刷新后重新认证。路由入口控制辅助使用体验，实际授权以服务端为准。Element Plus 提供中文对话框与操作确认，活动封面失败时显示 CSS 分类占位。

票档模块位于 `com.ticketing.ticket`，复用现有分层、认证与 CSRF。TicketService 统一维护销售窗口、可售状态、总库存和管理版本；与 SessionService 共享场次行锁维护跨表时间规则，未引入分布式锁。库存查询为 MySQL 事实计算，ON_SALE 管理状态不等同于正在售票。

MySQL 仍是未来交易事实来源。Redis 和 RabbitMQ 仅保留基础设施与健康检查，本阶段没有增加缓存、队列消费者或消息业务。库存预占扣减、用户注册、购票、支付、候补和核销均未实现。
