# 当前 API 契约

后端目录已统一为扁平技术分层，控制器位于`com.ticketing.controller`，服务位于`com.ticketing.service`，Mapper位于`com.ticketing.mapper`。前序目录整理保留了HTTP路径、权限与CSRF协议；第十二阶段新增的429/503响应见本文末尾。历史验收文档中的旧源码路径以当时提交为准，当前结构见[架构说明](01-architecture.md)。

## 第十阶段订单运营与模拟退款

以下新增端点均在`/api/v1`下，沿用最新账号状态/ADMIN角色检查、Session和写请求CSRF，响应no-store。匿名401、普通用户403、非法参数400、状态冲突409，不返回票券凭证、摘要或认证信息。

| 方法与路径 | 请求与响应 |
| --- | --- |
| GET /admin/orders | page默认1、1至10000；size默认10、1至50。可选orderNo/username精确匹配（最多64位）、正整数userId/activityId/sessionId、订单status、带时区createdFrom/createdTo。创建时间左闭右开，结束必须晚于开始；按created_at和id降序稳定分页 |
| GET /admin/orders/{id} | summary、waitlist、tickets、refund、aftercare；summary包含order、字符串userId、username、paidAt。tickets仅total/unused/used/voided数量，waitlist仅ID、状态和预留关联；不含入场凭证 |
| POST /admin/orders/{id}/refund | JSON仅`{"reason":"CUSTOMER_REQUEST"}`，可选原因CUSTOMER_REQUEST、SERVICE_ADJUSTMENT、SESSION_CANCELLED；最后一种只允许场次已取消。拒绝金额、用户ID、操作人、状态及任何其他字段 |

退款返回`{status,refund,aftercare}`。status为SUCCEEDED表示整单模拟退款已完成；重复成功返回原refundNo和退款记录，不再改变库存。MANUAL_REQUIRED表示未退款，aftercare提供原因，订单仍PAID、票券和支付不变。PENDING/CANCELLED/EXPIRED不能退款，409 REFUND_STATE_CONFLICT。

refund包含字符串orderId/refundNo/paymentNo/amountCents/operatorId（系统NULL）、reason、status=SUCCEEDED、refundedAt及simulated=true；不会伪造第三方资金回执。aftercare包含status=RETRY/MANUAL_REQUIRED/COMPLETED、reasonCode、中文message、attemptCount、lastAttemptAt、nextRetryAt。管理员主动退款也会留下可查人工待办，自动任务不无限重试人工状态；运营核对并修复原因后可重新明确提交模拟退款，不提供绕过已核销检查的强制接口。

用户订单新增refund（未退款为NULL）。REFUNDED显示“已模拟退款”，保留原payment；本单电子票为VOID，凭证接口409，不再提供二维码。历史异常明细缺失时skuId/skuName/quantity/unitPriceCents为NULL，显示待核对，canPay和canCancel为false，不伪造金额或数量；管理员可查询对应人工待办。

取消场次接口仍只快速持久化CANCELLED并返回场次；后台有界逐单处理PENDING→CANCELLED或PAID→REFUNDED。已核销、缺票及异常订单保留MANUAL_REQUIRED；已禁用买家不阻止善后。可刷新运营订单详情读取结果，不能把场次取消响应当作全场退款已完成。活动OFFLINE不触发自动售后。

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
| POST `/orders` | JSON仅 `{ "skuId":"10001", "quantity":2 }`，必需请求头Idempotency-Key；持久受理成功202返回PurchaseRequestView，终态同键重放200；不在HTTP内预占或创建订单 |
| GET `/purchase-requests/{requestId}` | 本人购票请求真实进度；不存在及非本人统一404，no-store |
| GET `/purchase-requests` | 本人最近50笔进行中的抢票申请，供刷新与导航恢复；no-store |
| GET `/orders?page=1&size=10` | 本人订单，按创建时间及ID降序，返回items/total/page/size；页码1至10000，每页1至50 |
| GET `/orders/limits?sessionId=10001` | 当前用户该场次限额 `{maxPerSession,usedQuantity,remainingQuantity}`；供表单展示，下单时重新校验 |
| GET `/orders/{orderId}` | 本人订单详情；不存在或属于其他用户统一404 |
| POST `/orders/{orderId}/cancel` | 无业务请求体；仅PENDING转CANCELLED并释放库存和限额，重复CANCELLED返回原单，其他状态409 |
| POST `/orders/{orderId}/pay` | 本人订单模拟支付；无业务请求体（允许空对象），不接收金额、用户ID等字段；成功200返回PaymentView |
| GET `/orders/{orderId}/payment` | 本人订单成功模拟支付流水；没有成功记录404 PAYMENT_NOT_FOUND，非本人订单404 ORDER_NOT_FOUND |

skuId为正十进制字符串且不能超过Java long，quantity为正INT整数，拒绝小数。未知创建字段（userId、价格、金额、状态、库存等）400；身份和价格只能来自服务端。幂等键为1至128位可见ASCII字符（不含空格），区分大小写；缺失或非法400。操作域固定CREATE_ORDER，同一用户同键同参返回同一个requestId及真实状态，不续期、不重复占预算或追加消息；同键异SKU/数量409 IDEMPOTENCY_CONFLICT，不同用户可共用键。V8将旧普通订单幂等事实登记为SUCCEEDED，旧订单已取消仍关联该原单，不重复预占。

PurchaseRequestView为 `{requestId,skuId,quantity,status,orderId,failureCode,failureMessage,acceptedAt,deadlineAt,serverTime}`，ID按字符串传输。状态为QUEUED（抢票排队）、PROCESSING（确认中）、SUCCEEDED（订单已创建）、FAILED（业务失败）、EXPIRED（排队超时）。后面三种为终态；只有SUCCEEDED有真实orderId。202只证明受理事务已提交，不表示占库存或可支付。默认容量200、排队120秒，配置须通过校验；首次受理在同一MySQL事务中申请容量、保存请求和Outbox。满额503 PURCHASE_QUEUE_FULL，带Retry-After=2，不返回排队成功。

购票命令消费者在MySQL事务内按用户→活动→场次→SKU→订单→购票请求→容量预算加锁，校验活动PUBLISHED、场次SCHEDULED及销售窗口、SKU ON_SALE、可售量；区间为 `[saleStartsAt,saleEndsAt)`。数量受SKU单笔上限和跨SKU用户场次限额约束，默认每人每场4张，PENDING/PAID占额，CANCELLED/EXPIRED/REFUNDED不占额。库存或不可售、限购及金额异常表现为请求FAILED和有限业务码及中文说明；临时数据库异常回滚并保持可恢复状态。成功订单、明细、库存预占及SUCCEEDED绑定必须一起提交。排队截止与订单15分钟支付截止独立，支付时间从正式订单创建开始计算。

OrderView字段：id/orderNo/activityId/activityTitle/sessionId/sessionTitle/sessionStartsAt/skuId/skuName/quantity/unitPriceCents/totalAmountCents/status/createdAt/expiresAt/paymentDeadlinePassed/canCancel/canPay/payment/serverTime。ID和金额分为字符串，数量为整数，时间UTC带Z、页面北京时间。skuName、单价和数量来自下单明细快照，活动/场次名称及开始时间是当前关联资料。总额为服务端单价乘数量，不采用客户端预计金额。payment无成功流水时为null，否则为PaymentView，列表通过JOIN读取，不逐订单额外查询。

状态为PENDING（待支付）、PAID（已支付）、CANCELLED（已取消）、EXPIRED（已过期）、REFUNDED（已模拟退款）。PENDING仅可支付、取消或过期，PAID仅能经管理员/场次善后整单模拟退款；REFUNDED不可支付或取消。支付窗口配置 `ticketing.order.payment-window-minutes` 默认15分钟；创建及查询使用MySQL UTC时间。PENDING截止前且活动PUBLISHED、场次SCHEDULED时canPay=true；明细完整的PENDING仍可主动取消，其他状态canCancel=false。超过截止但扫描尚未处理的PENDING仍占库存与限额，paymentDeadlinePassed=true且不能支付；只有收到EXPIRED才展示已过期。前端以serverTime和单调计时推进支付按钮的禁用提示，不能自行修改订单终态。

## 模拟支付与自动超时关闭

pay与payment沿用全部订单路径的Session和当前账号复查，POST需CSRF；未登录401、禁用账号401、无效CSRF403。用户只能访问本人，ADMIN无归属例外。客户端传金额、状态、身份等非空支付业务字段400，实际金额固定来自订单totalAmountCents快照。

PaymentView为 `{orderId,paymentNo,amountCents,status,paidAt,simulated}`，ID/金额分为字符串，status=SUCCEEDED，paidAt为UTC时间，simulated=true明确标记模拟业务，不接真实平台、不实际扣款。订单和支付时间/金额在同事务内一致，支付流水号唯一且同订单仅一条。

支付事务锁定用户→活动→场次→票档→订单。只允许未截止的PENDING，最终SQL要求 `expires_at>UTC_TIMESTAMP(6)`，锁等待不会延长期限；超过截止返回409 ORDER_EXPIRED。活动不再PUBLISHED或场次非SCHEDULED返回409 ORDER_UNPAYABLE；CANCELLED/EXPIRED返回409 ORDER_STATE_CONFLICT。库存必须locked足量，将locked等量转sold，状态、库存和SUCCEEDED流水同事务提交，失败全部回滚。SKU停售只禁止新订单，不撤销已占库存订单的支付资格；父级不可售则阻止继续支付。

重复支付PAID直接返回原PaymentView，既不新增流水也不变库存；即使此后父级下架，仍可查询或重放成功结果。请求结果不明时刷新本人订单或payment查询可恢复事实，不自动重放POST。支付或取消失败时Vue重新查询订单，展示竞争后的真实状态。

Spring Scheduling默认开启，启动即扫描MySQL的 `PENDING AND expires_at<=UTC_TIMESTAMP(6)`，强制使用既有idx_order_expiry，按expires_at/id有界读取；默认每批100、最大500、完成批次后间隔30秒。没有公开的手动过期接口。每笔独立事务，遵守同一锁序，系统只锁用户、不做ACTIVE校验；只有成功PENDING→EXPIRED才释放locked。PAID/CANCELLED/EXPIRED或未截止均无操作。失败记录后继续处理，游标前进、到尾回绕重试；重启从库中重新补偿，不依赖内存游标持久化。多个调度实例重复扫描仍幂等。

环境配置为ORDER_EXPIRY_ENABLED（默认true）、ORDER_EXPIRY_SCAN_DELAY_MS（默认30000）、ORDER_EXPIRY_BATCH_SIZE（默认100）。截止与实际关闭之间存在扫描延迟，大量积压、锁等待或错误重试会进一步延迟，但截止后支付SQL立即拒绝；不要将暂时仍PENDING误报为仍可支付或已释放。

前端数量范围按可售量、单笔上限及限额查询取最小值，金额用BigInt。首次提交前在sessionStorage按用户与SKU保存非敏感的数量、幂等键及已知requestId；不保存Session、CSRF或票券凭证。网络/5xx结果不明时手动重试原键，刷新与导航后可恢复，不换键重复创建。排队页每次请求最多10秒、每轮最多60次，间隔2至5秒，隐藏或卸载页面停止查询；停止轮询不伪装服务端已过期。只有真实SUCCEEDED才进入订单，抢票排队与缺票候补分开显示。

## 候补申请与查询

以下路径均以 `/api/v1` 为前缀，使用当前AccountPrincipal，Session与CSRF机制与订单一致。未登录或禁用账号401，无效CSRF403；ADMIN不享有查询他人候补的例外。公开活动及票档查询不额外查询账号。

| 方法与路径 | 请求与行为 |
| --- | --- |
| POST /waitlist | JSON `{ "skuId": "10001", "quantity": 2 }`；拒绝userId、状态、截止等未知字段；创建或返回本人同SKU同数量的有效记录 |
| GET /waitlist | page默认1、size默认10，上限分别10000/50，返回本人分页记录 |
| GET /waitlist/{id} | 本人候补详情，非本人或不存在404 WAITLIST_NOT_FOUND |
| POST /waitlist/{id}/cancel | 只取消WAITING；重复CANCELLED原样返回，ALLOCATED需走关联订单取消，其他状态409 |

skuId为正十进制字符串且不超过Java long，quantity为正INT整数，拒绝小数及未知字段（400）。申请时须在有效销售窗口、父级可售、账号有效、满足单笔及场次剩余额度，且可售库存小于请求数量；库存充足提示直接购买。相同有效记录同数量返回原记录，异数量409 WAITLIST_CONFLICT，数据库有效唯一键兜底。不要求客户端传幂等键。等待截止取数据库当前时间加WAITLIST_MAX_WAIT_MINUTES（默认60）与票档停售时间的较早值。等待不占库存或限额，可在其他票档购票；分配时再次检查，额度不足的队首会终结为EXPIRED并继续下一位。

WaitlistView字段：id/skuId/sessionId/activityId/activityTitle/sessionTitle/skuName/priceCents/quantity/status/createdAt/expiresAt/reservedOrderId/orderStatus/orderExpiresAt/canCancel。ID与金额分为字符串，时间带Z，界面北京时间。priceCents是当前票价，实际支付使用分配订单的价格快照。reservedOrderId及订单状态、截止在未分配时为null；已分配关联需匹配用户、场次、SKU、数量，否则409。canCancel仅表示WAITING可提交取消，最终以事务状态检查为准。

状态映射：WAITING等待候补、ALLOCATED已分配待支付、COMPLETED候补完成、CANCELLED已取消候补、EXPIRED候补已过期或失效。ALLOCATED的支付截止以orderExpiresAt为准，原expiresAt仅是等待申请截止。支付成功→COMPLETED；预留订单主动取消或自动过期→EXPIRED，状态联动与库存同事务。已分配不可直接取消候补，重复支付仍返回原流水。

同SKU有效队列按ID升序，队首数量不足不跳过；队首过期、账号禁用、父级下架/取消、停售或额度不足转EXPIRED后继续。入队及分配在同SKU锁内串行，锁顺序固定用户→活动→场次→SKU→订单→候补。候补订单固定WAITLIST_CONFIRM域，服务端唯一键waitlist-{候补ID}，复用服务端金额和库存条件更新。FIFO只承诺同SKU候补内部顺序，不保证优先于所有直接购票请求。

## 候补消息与补偿

订单取消/超时、管理员增加总库存，及候补入队/退出产生调度提示，同事务写outbox_event。事件类型WAITLIST_DISPATCH_REQUESTED、聚合TICKET_SKU，payload为字符串eventId/skuId及reason，无账号凭据。无公开消息、强制调度或修改候补状态接口。

发布任务默认每秒读取至多20条，每条短事务SKIP LOCKED认领，30秒租约，retry_count作为认领令牌。持久消息经correlated confirm且无mandatory退回才标SENT，确认超时5秒；失败按2秒起、最大300秒有界指数退避，总尝试10次后FAILED。应用重启或租约到期可以重新认领，旧令牌不能修改新任务状态。FAILED保留供运维定位，不代表候补必然失败。

RabbitMQ持久交换机ticketing.waitlist，路由inventory.available，持久队列ticketing.waitlist.dispatch；死信交换机ticketing.waitlist.dead，死信队列ticketing.waitlist.dispatch.dead，路由failed。消费者核对已提交Outbox与messageId、SKU，事务完成后确认；重复消息重查数据库，不重复创建订单。消费最多4次、间隔1/2/4秒，失败拒绝重新入队并进入死信，避免快速无限循环。

独立数据库补偿默认每60秒扫描100个仍有WAITING的SKU，每SKU至多处理50次队首；覆盖有库存未分配及无库存但队首已失效的情况。逐SKU隔离失败，游标前进并回绕，重启从数据库恢复。WAITLIST_MESSAGING_ENABLED=false关闭候补拓扑及消费，补偿仍运行；WAITLIST_JOBS_ENABLED=false关闭候补后台补偿。第十四阶段通用Outbox发布统一由OUTBOX_JOBS_ENABLED控制，同时服务候补和购票通道；购票消费和请求恢复分别由PURCHASE_MESSAGING_ENABLED、PURCHASE_JOBS_ENABLED控制。关闭任何通道仅供受控故障验证，正常部署均保持开启；不保证即时分配或吞吐指标。

## 电子票查询与入场凭证

以下均为 `/api/v1` 前缀，使用当前Session身份和最新账号状态复查；匿名/停用401，其他人的电子票或订单404，管理员通过本人查询接口也无归属例外。

| 方法与路径 | 请求与行为 |
| --- | --- |
| GET /tickets | 本人分页列表，page默认1、上限10000，size默认10、上限50；可选orderId正整数及status=UNUSED/USED/VOID |
| GET /tickets/{id} | 本人电子票详情，普通数据不含凭证明文、签名密钥或数据库摘要 |
| GET /tickets/{id}/credential | 本人UNUSED且所属订单PAID的完整凭证，返回 `{ticketId,credential}`，Cache-Control=no-store、Pragma=no-cache |
| POST /admin/checkins | ADMIN及CSRF，JSON仅接受 `{ "credential": "完整电子票凭证" }`，成功返回已核销票券资料 |

ElectronicTicketView字段为id/orderId/orderItemId/skuId/ownerId/ownerName/ticketNo/sequenceNo/status/activityId/activityTitle/sessionId/sessionTitle/sessionStartsAt/sessionEndsAt/city/venue/skuName/usedAt/checkedBy/createdAt。ID字符串，序号整数，日期UTC带Z，界面北京时间。票档名取订单明细快照，活动、场次、场馆和持票人名称取当前资料。UNUSED未使用、USED已核销、VOID已作废；usedAt与checkedBy在核销前为null。已用/作废凭证接口409，不再展示可入场二维码。

模拟支付成功时在原事务末端按明细数量生成独立电子票，与订单PAID、库存locked转sold、流水及候补COMPLETED原子提交。重复支付返回原流水，不再次签发；电子票签发与历史补票在订单锁内按唯一明细序号幂等，异常数量/关联/摘要拒绝并回滚。PENDING/CANCELLED/EXPIRED不出票。已付但尚无票的历史订单由受控补票任务处理，无公开补票或任意修改状态接口。

凭证格式 `TKT1.{version}.{ticketNo}.{signature}`，ticketNo为32字节安全随机数的Base64URL无填充编码，签名为HMAC-SHA256覆盖前三段，数据库仅保留完整凭证SHA-256摘要及密钥版本。核销签名和摘要均常量时间核对；拒绝未知格式/版本、篡改、非规范编码和摘要失配。合法持票人查询时重建再核对摘要，密钥无法对应时503 TICKET_KEY_UNAVAILABLE。完整凭证只在专用响应、二维码与POST JSON中传递，禁止放入URL、浏览器存储、日志或前端Store。

核销先解析和验证凭证，锁工作人员用户行复查ADMIN，再按活动SHARE→场次SHARE→SKU UPDATE→订单UPDATE顺序检查当前状态，最终以电子票UNUSED条件更新。SQL同时要求订单PAID、场次SCHEDULED，且数据库实际时间位于 `[场次开始-配置提前分钟,场次结束)`，默认提前60分钟。受影响行为1才成功，记录UTC核销时间及Session操作人；核销不改订单、支付或库存。活动下架不自动作废已付票，场次取消/结束拒绝核销。

核销成功200；重复USED返回409 TICKET_ALREADY_USED及“电子票已核销，请勿重复入场”，不覆盖原时间或人员；VOID返回409 TICKET_VOID；未付订单409 TICKET_ORDER_UNPAID；无效凭证400 INVALID_TICKET_CREDENTIAL；场次状态异常409 CHECKIN_SESSION_UNAVAILABLE；窗口外或状态竞争409 CHECKIN_UNAVAILABLE。无角色403，无效CSRF403，客户端伪造checkedBy等额外字段400。错误不回显完整凭证。

前端我的电子票支持本人分页、状态筛选及从已付订单按orderId查看；每张票详情独立请求凭证并本地生成二维码，隐藏/刷新/切换页面清理图片及敏感状态。管理员页面使用摄像头（需安全上下文及用户授权）、本地二维码图片识别或手动粘贴，识别后停止扫描，确认后提交一次；成功清空凭证输入并展示场次、票档及核销时间。自动识别不自动连续提交，后端仍独立保证最多一次核销。

## 密钥与受控历史补票

TICKET_CREDENTIAL_KEYS为JSON对象，版本号映射至Base64密钥；每个解码32至128字节，版本1至32767，TICKET_CREDENTIAL_CURRENT_VERSION指定当前签发版本（默认1）。拒绝缺失、弱长度、重复版本、非字符串值、非法JSON及当前版本缺失，启动日志不回显密钥。签名配置无固定默认值，轮换先保留旧有效映射再加新版本并更新当前版本，重启应用后新票使用新版本，旧票继续重建/验证。

TICKET_BACKFILL_ENABLED默认false，显式开启后启动扫描PAID订单，默认每批100、最多500，TICKET_BACKFILL_SCAN_DELAY_MS默认30000。每单独立READ_COMMITTED事务，先锁订单所属用户（停用也允许补票），再锁父级、SKU和订单，与支付一致；检查已有票再只补缺失序号，不修改已有票及财务/库存。不删除异常票，失败记录订单ID并继续，游标前进并回绕；无公开补票接口。受控批次完成后关闭开关。CHECKIN_OPENS_BEFORE_MINUTES默认60，可配置0至1440，服务端SQL最终判定窗口。

## 当前边界

电子票签发、展示与核销已实现。模拟支付不产生实际扣款，第十阶段整单模拟退款也不发生真实资金退回；真实支付平台和资金退款尚未接入。

## 第八阶段独立压测监测

仅显式加载scripts/performance/application-performance.yml的专用18080进程，将已有Actuator迁至 `/api/v1/admin/monitoring`，开放health、info、metrics。全部位于原ADMIN匹配器内，继续复查数据库最新账号状态及角色；匿名401、USER403、ADMIN可读。普通开发/生产配置仍只暴露原health/info，不公开JVM或连接池指标。脚本通过真实登录Session读取指标，未增加免认证监测、故障注入或测试数据管理HTTP接口。

业务请求路径及响应结构不变，压测日志只按固定路由名聚合，不使用用户、订单或票号标签。MySQL和RabbitMQ管理观测仅通过独立环境的本地工具获取，凭据和完整电子票不进入报告。接口SQL次数、故障恢复及库存审计结果见 [性能复现](performance/01-reproduce.md) 和同目录实际结果。

## 第九阶段内部协作调整

HTTP路径、请求字段、Session与CSRF、HTTP状态码及响应字符串状态保持原契约。OrderService应用入口内由OrderReservation、TradeResourceLocker、OrderStateService及同步OrderLifecycle协作；不对客户端暴露内部事务上下文或新增调度/库存写接口。普通和候补预留订单仍共享服务端金额、幂等和限购。

状态由枚举集中表达，序列化仍为原PENDING/PAID等值，不使用ordinal。库存事件只移动Java包与职责，既有类型WAITLIST_DISPATCH_REQUESTED、eventId/skuId/reason和RabbitMQ路由兼容。V5拒绝候补关联错误SKU；数量等业务关系异常仍回滚整个支付或关闭事务。前端拆分不改变路由、真实API、二维码清理或核销确认方式。架构说明见[模块边界](01-architecture.md)。

## 第十一阶段工作人员与操作审计

以下接口仍使用现有HttpSession身份。所有POST都需要当前CSRF令牌；ID通过十进制字符串传输。工作人员角色只授予核销权限，所有`/admin/**`仍仅ADMIN可用。

| 方法与路径（均在`/api/v1`下） | 权限 | 请求与行为 |
| --- | --- | --- |
| GET `/admin/staff` | ADMIN | 可授权的USER与CHECKIN_STAFF账号分页列表（不含ADMIN）；可按username筛选 |
| POST `/admin/staff/{id}/promote` | ADMIN | 将ACTIVE普通账号提升为CHECKIN_STAFF；不接受角色请求体 |
| POST `/admin/staff/{id}/demote` | ADMIN | 工作人员降回USER，并在同事务撤销其有效场次许可 |
| POST `/admin/staff/{id}/disable` | ADMIN | 停用工作人员并撤销有效场次许可 |
| GET `/admin/staff/{id}/sessions` | ADMIN | 分页查看该账号全部场次授权历史，包括已撤销记录 |
| POST `/admin/staff/{id}/sessions/{sessionId}/grant` | ADMIN | 授权ACTIVE CHECKIN_STAFF核销一个正常场次；重复授权返回当前关系 |
| POST `/admin/staff/{id}/sessions/{sessionId}/revoke` | ADMIN | 撤销已存在授权并保留历史记录 |
| GET `/admin/audit-logs` | ADMIN | 分页检索追加式管理审计 |
| GET `/checkins/sessions` | ADMIN、CHECKIN_STAFF | STAFF仅列出当前有效授权场次；ADMIN列出正常且未结束的场次，分页 |
| POST `/checkins` | ADMIN、CHECKIN_STAFF | 使用原核销请求`{ "credential": "完整电子票凭证"}`和同一核销服务；工作人员只可核销有效授权场次 |
| POST `/admin/checkins` | ADMIN | 保持第七阶段路径和管理员原有全场次核销行为 |

工作人员管理列表响应为`{id,username,role,status}`；场次授权响应含`staffId/sessionId/activityTitle/sessionTitle/startsAt/endsAt/sessionStatus/grantedBy/grantedAt/revokedAt`。STAFF场次列表只返回当前有效的授权关系；ADMIN调用同一路径时可查询正常且尚未结束的场次，授予字段为空。若凭证有效但目标场次不在STAFF授权范围内，返回403 `CHECKIN_SESSION_FORBIDDEN`；该检查在数据库核销事务内执行，页面过滤不能代替服务端授权。管理员的核销路径保持原策略，不要求另行授予场次。

审计查询参数为`operatorId`、`action`、`resourceType`、`resourceId`、`from`、`to`、`page`、`size`。时间必须是带偏移的ISO 8601并转换为UTC筛选；`from < to`；页码默认1且最大10000，每页默认10且最大50。响应审计条目含字符串ID、操作人ID/名称、动作、资源类型/ID、UTC时间、受控摘要、变更前后脱敏快照和requestId。允许的动作来自固定AuditAction枚举，资源类型限ACTIVITY/SESSION/TICKET_SKU/STAFF/ORDER。审计查询不会返回密码、会话Cookie、CSRF令牌、有效票凭证或签名密钥。

权限矩阵：USER不可访问管理及核销接口；CHECKIN_STAFF可看本人被授权场次并核销这些场次，不能查看审计或调用其他管理业务接口；ADMIN可使用既有运营接口、管理工作人员和查询全站审计，并保留原管理员核销能力。CurrentAccountFilter每次对这些受保护路由复查账号最新状态/角色；角色降级刷新Session身份，账号禁用使Session失效。工作人员授权另在核销事务中读取数据库事实，授权撤销、停用或降级不会依赖浏览器刷新来生效。登录/退出后仍须重新获取CSRF。

## 第十二阶段请求频率保护

`POST /api/v1/auth/login`、`/auth/register`、`/orders`、`/waitlist`在原有成功/失败协议外增加：

| HTTP | code | message | 响应头 |
| --- | --- | --- | --- |
| 429 | `TOO_MANY_REQUESTS` | `操作过于频繁，请在N秒后重试` | `Retry-After: N`、`Cache-Control: no-store` |
| 503 | `RATE_LIMIT_UNAVAILABLE` | `安全校验服务暂不可用，请稍后重试` | `Retry-After: N`、`Cache-Control: no-store` |

仍使用`ApiResponse` JSON，不返回Redis键、账号存在性、凭据或内部异常。登录错误（包括不存在、停用和密码错误）统一401 `INVALID_CREDENTIALS`及“用户名或密码不正确”。注册用户名重复仍按原409协议处理。

默认窗口及策略见[限流验收说明](22-redis-security-rate-limits-verification.md)。登录按连接IP和规范化账号+IP共同计数，正确/错误密码的放行尝试都计数，成功不清零；拒绝不延长窗口。所有购票提交（包括相同幂等键）共享本人30次/10秒预算，允许常规网络重试，耗尽后等待Retry-After再由用户重试，原幂等事实仍保留。候补本人10次/60秒。429/该503在业务调用前返回；前端展示中文等待秒数，不自动重放写请求或支付。无响应/其他5xx仍保留原订单同键确认策略。

已有有效Session时，CSRF不合法先403；匿名购票/候补通过CSRF后先401，停用账号先401并使Session失效。公开活动查询和已登录订单查询不依赖Redis限流服务。STAFF/ADMIN原权限矩阵保持不变。

## 第十三阶段会话创建与代理边界

无有效Session的`GET /api/v1/auth/csrf`及所有需要CSRF校验的写请求，在CsrfFilter前使用客户端IP的新会话预算，默认120次/60秒。无效、过期或伪造JSESSIONID不能绕过；429/503响应沿用上表，拒绝时不创建Session、不返回Set-Cookie、不调用Controller。这些匿名请求可能先得到429/503，再进入原CSRF或身份校验。HEAD csrf仍为403且不生成令牌。已有有效Session获取令牌不消耗新会话预算，Redis故障时仍可复用；登录和退出后仍须重新获取令牌。前端沿用统一中文错误和Retry-After提示，不自动重放写请求。

默认DIRECT模式只使用连接IP。TRUSTED_PROXY须显式配置受信代理IP/CIDR，并从连接端向外剥离受信节点，遇到最近的不可信节点即停止；只读取X-Forwarded-For。非受信连接忽略所有转发头，受信连接缺失、重复、过长、跳数过多或格式错误的头返回400 `INVALID_CLIENT_ADDRESS`，不创建新Session。X-Real-IP、Forwarded不作为身份来源。Spring自动转发头处理须保持none。部署前提和复现见[阶段十三验收](23-session-proxy-migration-verification.md)。
