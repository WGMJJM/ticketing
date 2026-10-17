# 当前 API 契约

统一前缀 /api/v1。成功响应为 ApiResponse 的 code=OK、message=操作成功、data=结果；业务错误沿用同一结构，data 为 null。当前使用400参数错误、401管理员未认证、403无权限或CSRF无效、404活动/场次不存在、409状态或版本冲突、500服务内部异常。错误文案为中文，不返回 SQL 或堆栈。

## 已实现公开读取

| 方法与路径（均在 /api/v1 下） | 说明 |
| --- | --- |
| GET /system/status | 原系统状态接口，表示 HTTP 可访问 |
| GET /activities | 已发布活动分页，支持 keyword/category/city/page/size |
| GET /activities/{id} | 已发布活动详情，草稿或下架均返回404 |
| GET /activities/{id}/sessions | 已发布活动下场次，按开始时间、ID升序；包含取消状态供用户识别 |

分页默认 page=1、size=12，页码1至10000，每页1至50条。data 为 items/total/page/size。关键词按名称字面包含匹配，城市精确匹配，类别为 CONCERT/FESTIVAL/STAGE/EXHIBITION/ESPORTS。公开列表固定 PUBLISHED，按最近发布时间、ID降序，传入 status 也不能读取隐藏活动。

活动返回 id/title/category/city/venue/summary/description/coverUrl/status/publishedAt/createdAt/updatedAt/version。详情纯文本展示，不解析 HTML。场次返回 id/activityId/title/startsAt/endsAt/saleStartsAt/status/version。ID 为字符串，日期为带 Z 的 ISO 8601；前端按北京时间展示。场次状态 SCHEDULED/CANCELLED/ENDED，前端显示正常/已取消/已结束，状态为取消或已结束的场次不能选择。正常场次允许查看票档，是否处于售票时间由后端决定。

## 已实现管理接口

以下全部需要 ADMIN 身份。当前由受控开发管理员提供，默认不开启。写请求必须携带认证后的 CSRF 令牌。

| 方法与路径（均在 /api/v1 下） | 请求与行为 |
| --- | --- |
| GET /admin/session | 返回 username/csrfHeader/csrfToken；Cache-Control 为 no-store |
| GET /admin/activities | 同公开筛选，加 status=DRAFT/PUBLISHED/OFFLINE，可查全部状态；按创建时间、ID降序 |
| GET /admin/activities/{id} | 管理活动详情 |
| POST /admin/activities | 创建草稿；title/category/city/venue/summary/description，coverUrl可选 |
| PUT /admin/activities/{id} | 同创建字段，必须提供version；只能编辑草稿或下架活动 |
| POST /admin/activities/{id}/publish | 请求version；DRAFT/OFFLINE → PUBLISHED，更新最近发布时间 |
| POST /admin/activities/{id}/offline | 请求version；仅PUBLISHED → OFFLINE |
| GET /admin/activities/{id}/sessions | 管理场次列表 |
| POST /admin/activities/{id}/sessions | title/startsAt/endsAt/saleStartsAt，时间须有偏移或Z |
| PUT /admin/sessions/{id} | 同创建字段加version，只能修改正常场次，不允许改所属活动 |
| POST /admin/sessions/{id}/cancel | 请求version；仅SCHEDULED → CANCELLED，不支持恢复 |

活动字段长度上限为标题200、城市64、场馆200、简介500、详情20000、封面2048。封面只接受HTTP(S)，服务端不抓取、不上传。结束时间晚于开始，开售早于开始；输入2030-05-01T19:00:00+08:00将存为UTC并返回2030-05-01T11:00:00Z。每个活动最多200个场次；活动发布不要求已存在场次，用户看到明确空状态。

version从0开始，成功更新递增；遗漏版本为400，陈旧版本或非法状态转换为409。不能通过直接提交status绕过发布/下架接口。开发管理员不使用业务user_account表，也不代表已经实现普通用户登录。

## 已实现票档与库存管理

以下路径均在 `/api/v1` 下，写操作复用 ADMIN 与 CSRF。读取票档不代表已经支持下单。

| 方法与路径 | 语义 |
| --- | --- |
| GET `/activities/{activityId}/sessions/{sessionId}/tickets` | 已发布活动下的场次票档，只返回 ON_SALE；校验父子归属，按价格分、ID升序，禁止缓存 |
| GET `/admin/sessions/{sessionId}/tickets` | 查看全部管理状态、库存和服务端销售状态 |
| POST `/admin/sessions/{sessionId}/tickets` | 创建草稿，锁定和已售库存均为0 |
| PUT `/admin/tickets/{ticketId}` | 编辑基本信息或总库存，必须提供 version |
| POST `/admin/tickets/{ticketId}/publish` | 请求 `{version}`，DRAFT/OFF_SALE → ON_SALE |
| POST `/admin/tickets/{ticketId}/offline` | 请求 `{version}`，仅 ON_SALE → OFF_SALE |

创建和编辑请求：name（必填，100字）、priceCents（非负整数分字符串，最大9223372036854775807）、totalStock（0至2147483647整数）、saleStartsAt/saleEndsAt（带时区ISO日期）、maxQuantityPerOrder（1至2147483647整数），编辑额外必须提交version。禁止提交 lockedStock/soldStock/availableStock/status/sessionId 等只读字段，未知字段返回400。整数参数不接受小数截断。

响应包含 id/sessionId/name/priceCents、totalStock/lockedStock/soldStock/availableStock、saleStartsAt/saleEndsAt、maxQuantityPerOrder、status/saleStatus/serverTime/version。价格分与ID为字符串，库存和版本为整数，时间返回UTC带Z；金额在前端用BigInt展示为人民币元。

窗口统一为 `场次开售 <= 票档开售 < 票档停售 <= 场次开始`，实际销售区间左闭右开。场次开售是最早允许时间，不要求所有票档同一时刻开售。修改场次会检查所有已有票档（包含草稿、下架），冲突409，不会静默修改票档时间。已取消、已开始、已结束的场次不能新建、编辑或启用票档，但已有启用票档仍可下架。发布时销售窗口必须尚未结束；活动未发布时允许准备票档，但销售状态为不可售。

ON_SALE 仅为管理状态。后端先判断活动发布、场次正常且未结束、票档启用及父子窗口一致，否则 UNAVAILABLE（暂不可售）；然后判断停售边界 STOPPED（已停售）、未开售 NOT_STARTED（暂未开售）、可售量为0 SOLD_OUT（已售罄），最后为 SELLING（正在售票）。活动隐藏时公开接口404；已取消/结束的公开场次可返回 ON_SALE 票档，但只会显示暂不可售。前端不得用本机时钟重算销售状态；“正在售票”仍不代表本阶段能下单。

同一场次票档名唯一，重复409；陈旧版本、非法状态转换、总库存低于锁定加已售为409。已启用销售只能改总库存，其他信息必须先下架。可售量只计算不存储，管理写接口从不改锁定和已售库存。每场次最多200票档，列表有界；本阶段无库存预占或购票扣减接口。

## 待开发方向

用户注册登录及归属权限 → 下单锁票 → 模拟支付与超时关单 → FIFO候补 → 电子票核销。金额用人民币分整数、ID用字符串、日期带时区；下单只接收SKU和数量，价格由服务端计算。订单幂等、库存事务、Outbox、消费者幂等与限购方案仍遵循数据库设计，本轮没有提供订单/支付/候补占位成功接口。
