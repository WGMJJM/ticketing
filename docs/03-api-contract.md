# 当前 API 契约

统一前缀 /api/v1。成功响应为ApiResponse的code=OK、message=操作成功、data=结果；业务错误沿用同一结构，data为null。使用400参数错误、401未认证或账号失效、403无权限或CSRF无效、404资源不存在或订单不属于本人、409库存/限购/幂等/状态/版本冲突、500服务内部异常。错误文案为中文，不返回SQL或堆栈。

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

以下全部需要数据库账号的ADMIN身份和Session Cookie。写请求必须携带当前会话CSRF令牌，旧Basic认证不再接受。

| 方法与路径（均在 /api/v1 下） | 请求与行为 |
| --- | --- |
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

version从0开始，成功更新递增；遗漏版本为400，陈旧版本或非法状态转换为409。不能通过直接提交status绕过发布/下架接口。普通用户访问管理接口403，未登录401，缺失或无效CSRF写请求403。

## 用户认证与会话

以下路径均在 `/api/v1` 下。所有认证响应（含失败）禁用缓存，返回ApiResponse，不包含密码哈希、Cookie值或Session ID。

| 方法与路径 | 请求和响应 |
| --- | --- |
| GET `/auth/csrf` | 公开，创建或读取会话CSRF；data为 `{headerName,token}` |
| POST `/auth/register` | JSON `{username,password}`，需CSRF；返回 `{id,username,role}`，不自动登录 |
| POST `/auth/login` | `application/x-www-form-urlencoded`，字段username/password，需CSRF；成功返回当前用户并建立Session |
| POST `/auth/logout` | 无业务请求体，需CSRF；销毁Session、清除Cookie，返回data=null；可幂等退出匿名会话 |
| GET `/auth/me` | 当前登录用户 `{id,username,role}`，未登录401；不接受其他用户的身份选择 |

用户名3至32位ASCII字母、数字、下划线，字母开头；大小写不敏感。密码8至72个字符且UTF-8编码不超过72字节，禁止控制字符；密码按原样验证，不自动去空格。注册只允许两个字段，携带role/status/id/userId等其他字段400；重复用户名409，固定创建USER/ACTIVE。成功注册不会自动创建已登录身份。

登录使用Spring Security标准表单过滤器；错误用户名和密码统一401及“用户名或密码不正确”，禁用账号401及中文禁用提示。成功登录更换Session ID并保存SecurityContext；后续只依靠Cookie恢复身份，Basic Authorization无效。role仅为USER或ADMIN，ID为字符串。后续服务通过AccountPrincipal获取用户ID，不能信任前端userId。

GET auth/me及全部admin和orders接口在授权前复查数据库当前账号；禁用或删除账号清除认证、销毁Session并清除Cookie，返回401、code=ACCOUNT_UNAVAILABLE及中文提示。恢复账号状态也不能复活旧会话，必须重新登录。角色变化刷新当前认证与会话中的身份，ADMIN变USER后管理接口403，auth/me返回USER；账号合法授权恢复后以数据库最新角色为准。无效CSRF写请求仍优先403。公开活动、场次、票档及认证端点不额外复查账号。变化在提交后的下一次有效受保护请求检查时生效，不追溯取消已通过检查或已执行的操作；订单创建/取消事务另加用户行锁检查ACTIVE。

CSRF保存在HttpSession，保留默认XOR保护。客户端先GET csrf，把响应token放到headerName指定的头，再提交注册、登录、退出或管理员写操作。登录和退出会清除旧CSRF，随后必须重新GET csrf。前端每次写请求前获取当前令牌，403不会自动重放写操作。Cookie为HttpOnly、SameSite=Lax，Session空闲30分钟，禁止URL会话跟踪；Secure默认true，仅本地HTTP显式配置false。前端同源访问Vite代理，服务端未开放跨域凭据访问。

参考实现依据：[Spring Security标准表单登录](https://docs.spring.io/spring-security/reference/6.5/servlet/authentication/passwords/form.html)、[CSRF端点及登录退出刷新](https://docs.spring.io/spring-security/reference/6.5/servlet/exploits/csrf.html)。

## 已实现票档与库存管理

以下路径均在 `/api/v1` 下，管理写操作复用ADMIN与CSRF。公开票档查询无需身份，下单使用下面的订单接口。

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

ON_SALE仅为管理状态。后端先判断活动发布、场次正常且未结束、票档启用及父子窗口一致，否则UNAVAILABLE（暂不可售）；然后判断停售边界STOPPED（已停售）、未开售NOT_STARTED（暂未开售）、可售量为0 SOLD_OUT（已售罄），最后为SELLING（正在售票）。活动隐藏时公开接口404；已取消/结束的公开场次可返回ON_SALE票档，但只会显示暂不可售。前端不得用本机时钟重算销售状态；提交订单必须再次在事务锁内检查，列表展示不能保证随后下单成功。

同一场次票档名唯一，重复409；陈旧版本、非法状态转换、总库存低于锁定加已售为409。已启用销售只能改总库存，其他信息必须先下架。可售量只计算不存储，管理写接口从不改锁定和已售库存。每场次最多200票档，列表有界；订单预占和释放会递增票档version，陈旧的管理表单需刷新后重试。

## 购票订单与库存预占

全部 `/orders` 路径需要当前有效Session，USER与ADMIN均只能操作自己的订单。管理员没有跨用户例外。创建和取消必须携带当前CSRF；请求及响应沿用ApiResponse，敏感响应不缓存。

| 方法与路径（均在 /api/v1 下） | 语义 |
| --- | --- |
| POST `/orders` | JSON仅 `{ "skuId":"10001", "quantity":2 }`，必需请求头Idempotency-Key；成功200返回OrderView |
| GET `/orders?page=1&size=10` | 本人订单，按创建时间及ID降序，返回items/total/page/size；页码1至10000，每页1至50 |
| GET `/orders/limits?sessionId=10001` | 当前用户该场次限额 `{maxPerSession,usedQuantity,remainingQuantity}`；供表单展示，下单时重新校验 |
| GET `/orders/{orderId}` | 本人订单详情；不存在或属于其他用户统一404 |
| POST `/orders/{orderId}/cancel` | 无业务请求体；仅PENDING转CANCELLED并释放库存和限额，重复CANCELLED返回原单，其他状态409 |

skuId为正十进制字符串且不能超过Java long，quantity为正INT整数，拒绝小数。未知创建字段（userId、价格、金额、状态、库存等）400；身份和价格只能来自服务端。幂等键为1至128位可见ASCII字符（不含空格），区分大小写；缺失或非法400。操作域固定CREATE_ORDER，同一用户同键同参返回原单，同键异SKU/数量409 IDEMPOTENCY_CONFLICT，不同用户可共用键。旧单已取消仍返回CANCELLED，不重复预占；创建失败整个事务回滚，同键可以重新尝试。

下单锁定父级并统一校验活动PUBLISHED、场次SCHEDULED及销售窗口、SKU ON_SALE、可售量；区间为 `[saleStartsAt,saleEndsAt)`。数量还受SKU单笔上限和跨SKU用户场次限额约束，默认每人每场4张，PENDING/PAID占额，CANCELLED/EXPIRED不占额。限额查询只是即时展示，无法替代服务端事务校验。库存或不可售409（TICKET_UNAVAILABLE/STOCK_UNAVAILABLE），超限409 PURCHASE_LIMIT，金额乘法溢出400 ORDER_AMOUNT_INVALID。数据库写入异常整体回滚，不返回半成品成功。

OrderView字段：id/orderNo/activityId/activityTitle/sessionId/sessionTitle/sessionStartsAt/skuId/skuName/quantity/unitPriceCents/totalAmountCents/status/createdAt/expiresAt/paymentDeadlinePassed/canCancel/serverTime。ID和金额分为字符串，数量为整数，时间UTC带Z、页面北京时间。skuName、单价和数量来自下单明细快照，活动/场次名称及开始时间是当前关联资料。总额为服务端单价乘数量，不采用客户端预计金额。

当前只创建PENDING（待支付）和主动转CANCELLED（已取消），保留模型中的PAID（已支付）/EXPIRED（已过期）供后续业务使用，当前没有支付或自动过期接口。支付窗口配置 `ticketing.order.payment-window-minutes` 默认15分钟；超过截止的PENDING仍占库存与限额，paymentDeadlinePassed=true且canCancel=true。该标志由后端查询时计算，页面显示截止提醒及主动取消，不伪装为已自动取消。支付功能尚未开放；未来支付必须拒绝截止及非PENDING订单。

前端数量范围按可售量、单笔上限及限额查询取最小值，金额用BigInt。确认提交期间禁止重复点击，每次新尝试生成UUID幂等键；当前页面遇网络/5xx结果不明时保留键和数量供用户手动重试或查看我的订单，不自动重放POST。刷新或离开页面后先查订单，不能假定未创建。取消成功后重新查询订单，活动页通过刷新票档获取释放后的库存。

## 待开发方向

第五阶段模拟支付与自动超时关单，之后FIFO候补、电子票及核销。Outbox与消费者幂等尚未接入。本轮完成真实订单、预占和主动取消后停止，没有模拟支付成功、候补分配或电子票接口。
