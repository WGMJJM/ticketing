# 第七阶段电子票生成与入场核销验收

验收日期2026-10-17，Windows、Java 21、Spring Boot 3、真实MySQL 8.4及RabbitMQ 4.1。基于原main的支付、候补和认证体系实现，保持单Maven及com.ticketing根包，业务代码位于checkin。复用electronic_ticket，V1至V4未改，无新增迁移、真实支付或退款。

## 实现与核心文件

- `backend/src/main/java/com/ticketing/checkin/service/TicketIssuer.java`：在已锁定PAID订单的事务中，检查已有票数量/序号/关联/摘要，按缺失明细序号签发独立电子票。
- `order/service/OrderService.java`：仅增加出票依赖和支付事务末端调用，未重构原订单、支付、候补或RabbitMQ；支付、库存结算、流水、候补完成及出票一起提交。
- `checkin/security/TicketCredentialSigner.java`：安全随机票号、HMAC-SHA256、完整凭证摘要、规范格式、密钥版本及严格启动配置校验。
- `checkin/mapper/ElectronicTicketMapper.java`、`entity/ElectronicTicket.java`：复用表/约束，持票人展示查询、有界历史扫描及带数据库时间窗口的UNUSED条件核销。
- `checkin/service/ElectronicTicketService.java`：本人分页/详情/凭证、管理员核销及遵守统一锁序的补票；`TicketBackfillJob.java`默认关闭，显式启用后逐单事务、有界批量、失败隔离。
- `checkin/controller/ElectronicTicketController.java`、`AdminCheckinController.java`：本人查询及ADMIN/CSRF核销，凭证响应no-store；SecurityConfig共用匹配器纳入电子票端点。
- `frontend/src/views/ElectronicTicketsView.vue`、`AdminCheckinView.vue`、`api/electronicTickets.ts`：本人票券筛选/详情/独立二维码、已付订单入口、摄像头/本地图片识别及手动核销。导航、个人中心、运营入口、平台介绍与样式同步。
- `backend/src/test/java/com/ticketing/checkin/ElectronicTicketIT.java`新增24项真实集成测试；`TicketCredentialSignerTest.java`新增5项签名与配置测试，账号过滤新增2条路径回归。原订单/候补测试增加本用例票券清理。
- AGENTS、架构、数据库设计、API契约、开发计划、本地开发及.env.example已更新。README继续只留本地。

## 签发与事务一致性

普通和候补订单成功付款，先沿原用户UPDATE→活动SHARE→场次SHARE→SKU UPDATE→订单UPDATE锁序完成状态/库存/流水及候补联动，再调用MANDATORY出票服务。只允许PAID；order_id、order_item_id、sku_id、owner_id全部取数据库，quantity对应序号1至quantity，每个购买单位一张UNUSED票。数据库唯一票号、摘要及(order_item_id,sequence_no)索引最终兜底。每次插入检查影响行数，第二张失败也回滚第一张、PAID、库存转售出、流水及候补完成，不留下半张或部分出票。

重复支付返回原成功流水，不再签发；重复出票在同一订单锁内校验并跳过已有序号。已付历史单通过默认关闭的TICKET_BACKFILL_ENABLED显式启用；每批默认100、上限500，每单独立事务，先锁所属用户（停用也允许），再锁父级、SKU、订单及已有票。只补缺，已有USED/VOID及票号不改不删。数量/序号/关联/摘要异常则保留所有现有票、记录订单ID并继续后续，游标前进并回绕；重启从数据库重新扫描。没有公开补票、作废或删除接口。

## 签名凭证与核销最多一次

票号使用SecureRandom生成32字节，经Base64URL无填充编码。完整凭证为TKT1.版本.票号.签名，HMAC-SHA256覆盖前三段ASCII文本，数据库只保存完整凭证SHA-256摘要及版本；列表/详情不返回密钥、摘要或凭证明文。合法持票人查看时重建并校验摘要，专用响应Cache-Control=no-store及Pragma=no-cache。前端只在当前组件内本地编码二维码，隐藏/刷新/页面切换清理，完整凭证不放URL、Store、浏览器存储或应用日志。

密钥来自环境变量版本映射，Base64解码32至128字节、版本1至32767，缺失、非法/重复版本、非字符串值、尾随JSON或当前版本缺失均拒绝启动，不使用固定默认值，也不将解析原文附加到异常。轮换保留旧有效版本，当前版本只影响新票；旧票仍按自身版本验证。签名和数据库摘要使用MessageDigest.isEqual比较，未知格式/版本、篡改、非规范编码及摘要失配均拒绝。

核销先验证凭证，再锁真实工作人员用户行并复查ADMIN，按父级→SKU→订单顺序检查当前事实，最终执行带UNUSED、PAID、SCHEDULED及MySQL实际时间窗口的条件更新。默认窗口为演出前60分钟至场次结束，不因等锁跨过结束时间而延长。影响行数1才算首次成功，同语句记录USED、used_at及可信adminId；重复扫码409“电子票已核销，请勿重复入场”，原人员/时间不覆盖。VOID、未付订单、场次取消/结束及窗口外拒绝。核销不改变已售库存、金额或订单支付状态。

## 自动化验证实际结果

| 验证 | 结果 |
| --- | --- |
| `mvn -f backend/pom.xml clean verify` | 42项基础测试全部通过，失败0/错误0/跳过0 |
| `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/start-backend.ps1 -VerifyDatabase` | 42项基础测试+108项真实集成测试，共150项全部通过 |
| 电子票新增真实集成测试 | 24项全部通过，真实HTTP Session/Cookie/CSRF及MySQL独立事务 |
| 既有回归 | 原84项活动、认证、迁移、订单/支付/重启、票档及真实RabbitMQ候补测试全部通过 |
| `cd frontend; npm ci; npm run build` | npm ci成功，安装143包、审计144包；严格TypeScript检查及Vite生产构建通过，联调修正后重新构建通过 |
| Flyway与文件检查 | 校验4个已应用迁移通过，V1至V4未改，git diff --check通过；README/密钥/日志/构建产物继续忽略 |

新增24项覆盖：按量独立出票与摘要；重复支付/并发重复出票；PENDING/CANCELLED/EXPIRED不出票；候补支付出票；第二张失败导致支付全回滚；出票失败回滚候补完成；历史未出票并发补齐；部分历史序号补齐且保留旧票；异常序号拒绝；有界扫描隔离坏订单；本人归属/管理员无越权例外及筛选分页；凭证禁缓存与数据库仅摘要；正常核销实际人员与库存不变；重复扫码保留首次时间人员且隐藏旧二维码；8个真实HTTP并发事务仅1成功；非法签名/票号/版本；正确HMAC但摘要/版本失配；轮换后旧票和新票均可用；核销窗口/结束/取消；VOID及未付订单；真实角色/匿名/CSRF/伪造人员字段；旧管理员撤权与禁用会话；核销写入后失败全回滚；补票与核销并发不反向加锁且保留已用票。

并发测试检查最终票券状态、时间、操作人及库存，不只检查HTTP状态。历史测试构造上线前PAID/成功流水事实，不删除已签发票来制造缺失；部分票直接作为历史夹具插入。故障注入仅在测试Mapper中启用，正常写入通过SqlSessionTemplate获取真实Mapper并使用同一事务连接，随后注入异常，数据库确实执行写入后验证回滚，不以Mock替代核销竞争。补票扫描测试将游标限定到自身新夹具，避免测试随机密钥处理其他用户历史订单。

## 本地历史兼容验证

真实本地数据库原有两笔PAID：900116购买2张、900394候补购买1张，均尚未出票。使用私有环境中的随机签名密钥，受控启动补票任务，批量2，自动补齐3张；查询每单票数与数量一致后关闭开关并恢复普通启动。没有删除重建已有票，没有改变订单支付或库存。证据保存在忽略的.local/checkin-historical-backfill.tsv，密钥仅留.env，不公开输出。

## 浏览器真实联调

活动900980、场次900886、SKU900897为明确标记演示的初始资料；其余订单、票券、识别、核销及状态全部由真实页面/API完成，没有SQL修改业务终态替代联调。

1. 用户900028登录，选择独立二维码体验票2张，单价¥168.00、总额¥336.00，创建真实订单900825。
2. 确认模拟支付后订单PAID，唯一流水e48530c44fcf473389b703841f66ce8f，金额33600分；出现“查看本单电子票”。
3. 本单电子票180/181分别为第1/2张，票号及二维码不同。分别打开并本地展示真实凭证二维码，切换后旧二维码初始已清理；普通用户进入核销页面被跳转个人中心并提示无权限。
4. 管理员900027登录。读取第一张电子票页面的实际二维码截图，经ZXing真实图片识别、人工确认后核销成功。再次识别并提交同一码，显示“电子票已核销，请勿重复入场”。
5. 用户重新查看180，显示核销时间，无法再展示有效二维码。181仍未使用，保存其独立二维码；管理员真实识别该二维码。
6. 当前环境摄像头启动失败，出现中文错误且图片/手动输入仍可用。另选不含二维码图片时，旧凭证被清空、确认按钮禁用；随后将上一轮真实解码的第二张凭证粘贴至输入框，确认后独立核销成功。
7. 用户刷新本单列表，两张均USED并显示各自时间。数据库180首次核销为UTC14:19:33.645770，181为UTC14:22:11.057500，checked_by均900027；重复扫描未覆盖首次时间。
8. 最终SKU total=20、locked=0、sold=2、available=18，订单仍PAID，只有一条33600分成功流水。全库PAID明细与电子票数量不一致的订单为0。

浏览器已验证真实二维码图片识别、手动核销、重复拒绝、刷新恢复、角色限制、无效图片清理及摄像头不可用后的备用路径。**当前环境未能启用摄像头，未完成真机摄像头授权及连续识别的端到端验证，不将此项报告为通过。** 摄像头代码使用ZXing标准API，识别即停扫并等待确认，页面退出释放已取得的媒体流；仍需在有摄像头的安全上下文中验证实际设备。

本地证据：.local/checkin-two-tickets.png、checkin-first-ticket.png、checkin-second-ticket.png、checkin-first-success.png、checkin-duplicate-rejected.png、checkin-second-success.png、checkin-completed-tickets.png及checkin-browser-db.tsv。截图及二维码不提交Git；最终交付截图选择已核销列表，不公开可复用的有效凭证。测试日志为.local/checkin-acceptance-maven.log、checkin-acceptance-it.log，前端日志为checkin-npm-ci.log及checkin-acceptance-frontend.log。

## 交付边界

仅完成第七阶段电子票生成、展示、补票及核销，继续保持MySQL事务权威、Session+Cookie+CSRF、既有订单/候补/MQ设计。不开发真实支付、退款、员工管理或整体架构重构，不声称具备未经压测的高并发抢票能力。main按功能提交、整体本地验证后推送，不强推；README、签名密钥、本地账号、日志与构建产物不上传。
