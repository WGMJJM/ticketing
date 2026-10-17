# 第四阶段购票订单与库存预占验收

验收日期2026-10-17，Windows、Java 21、真实MySQL 8.4。基于main的既有活动、票档和Session认证实现，未新增Maven模块、Redis库存或分布式锁。本阶段完成后停止。

## 实际功能与核心文件

- `backend/src/main/java/com/ticketing/order/`：请求DTO、订单实体、Mapper、Service和Controller，真实创建、本人分页/详情、场次额度查询、幂等和主动取消。
- `backend/src/main/resources/db/migration/V4__order_user_session_index.sql`：用户/场次/状态索引；复用既有订单和唯一明细，V1至V3未修改。
- `event/service/SessionService.java`、`ticket/service/TicketService.java`：与订单共享父级锁序、READ_COMMITTED和统一销售状态；库存仍保留版本和条件约束。
- `config/SecurityConfig.java`：订单路径纳入认证和当前账号复查，沿用Session Cookie及CSRF；公开查询仍跳过账号复查。
- `common/controller/ApiExceptionHandler.java`：缺失必需请求头或查询参数返回400。
- `application.yml`、`.env.example`：集中声明每人每场默认4张、支付截止默认15分钟。
- `frontend/src/components/PurchasePanel.vue`、`OrderCard.vue`、`views/OrdersView.vue`、`api/orders.ts`、`order.css`：真实数量、预计金额、确认下单、订单展示和取消；整合现有活动页、导航、登录返回与路由。
- `backend/src/test/java/com/ticketing/OrderManagementIT.java`：13项真实HTTP/MySQL订单测试；既有迁移、公开访问和身份过滤测试同步更新。
- 数据库设计、API契约、架构、计划、本地开发和AGENTS已更新；演示SQL说明同步真实下单能力。README继续仅本地忽略。

## 一致性协议

下单/取消固定使用READ_COMMITTED事务，锁序为用户UPDATE→活动SHARE→场次SHARE→SKU UPDATE→已有订单UPDATE。管理场次/票档先活动SHARE、再场次UPDATE、再SKU更新；活动变更只锁活动。发现父级ID的初始查询不决定销售状态，锁定后读取最新数据再校验。因此下架、取消或管理改库存与购买以锁获得顺序生效，后到操作看到已提交结果，不能用陈旧快照放行。

同用户的所有占额和释放先锁user_account行，锁内SUM该用户同场次PENDING/PAID明细quantity；不同SKU共享限额，取消/失败不遗留额度。没有重复维护quota表或可售库存字段。V4复合索引支持统计。该方案也会串行化同用户不同场次请求，是第一版的明确取舍；未来支付、超时及候补创建必须继续遵守同一用户锁和父级锁序，后台释放不应因账号停用跳过。

库存仍执行带可售量、ON_SALE及数据库销售时间条件的原子UPDATE，预占与明细、PENDING订单及价格快照同事务。释放要求locked_stock足量，并与PENDING→CANCELLED同事务。每次预占/释放递增SKU版本，管理库存更新必须满足版本和总库存不低于locked+sold。核心约束为locked、sold非负且locked+sold不超过total。

用户锁内先查固定CREATE_ORDER域的幂等键和SHA-256规范参数哈希；同键同参返回原单，异参409，不同用户可同键。六个并发请求只会占用一次库存。最终数据库唯一键继续兜底；任何写入异常穿透事务回滚，不在失败事务内捕获后返回成功。已取消原单重放不重新预占。

## 自动化验证实际结果

| 命令 | 实际结果 |
| --- | --- |
| `mvn -f backend/pom.xml clean verify` | 成功，33项基础测试，0失败、0错误、0跳过 |
| `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/start-backend.ps1 -VerifyDatabase` | 成功，33项基础测试及46项真实MySQL集成测试，共79项，全部通过 |
| `cd frontend` 后 `npm ci` | 成功，按锁文件安装107个包 |
| `npm run build` | 成功，vue-tsc严格类型检查及Vite生产打包通过 |
| 启动打包后的Spring Boot | 成功，8080启动，Flyway校验4个迁移、当前版本4；复用本地已有镜像 |

46项集成测试组成：活动场次6、认证13、迁移与约束5、订单13、Secure Cookie 1、票档8。原认证/旧会话撤权、活动、票档并发编辑及CSRF均回归通过。普通构建不执行需要MySQL的IT，不能将其33项当作数据库验收。

订单13项覆盖：

| 范围 | 最终数据库断言及业务结果 |
| --- | --- |
| 正常下单、快照和本人列表 | 锁定量增加2；12345分×2=24690分；后改票档名/价格仍返回原明细快照 |
| 参数、只读字段、头部、限购、金额溢出 | 拒绝非法输入，订单0、库存0、占额0；服务端拒绝身份/价格伪造 |
| 库存不足、最后一张竞争 | 两个用户同时真实HTTP购买最后一张，200/409各一；最终订单1、明细1张、locked=1 |
| 六请求同键并发 | 同一订单ID、订单1、locked=2；异SKU/数量409；其他用户可同键；取消后重放返回原CANCELLED |
| 跨SKU场次限购 | 同用户两个SKU各买3张并发，仅一个成功；再买1张达到4，继续购买拒绝，库存与有效数量一致 |
| 最后一步写入故障 | 库存预占和订单INSERT真实执行后，明细写入注入DataIntegrityViolationException；最终库存0、订单0、占额0，同键重试成功 |
| 重复取消及取消/创建竞争 | 五次并发取消均返回原取消结果，只释放一次；随后重复取消与新建并发仍守恒 |
| 截止语义 | 改为已截止夹具后查询仍PENDING、截止标志true、库存和限额仍占用；主动取消才释放 |
| PAID/EXPIRED兼容计数 | 用隔离SQL夹具验证PAID占额且不能主动取消、EXPIRED不占额；没有增加支付或超时业务接口 |
| 归属及认证 | 其他USER和ADMIN详情/取消均404，匿名及已禁用旧Session401，缺失/错误CSRF403，无额外预占 |
| 销售状态及时间 | 活动下架、场次取消、未开售、到停售边界、票档下架均拒绝；父级不可售仍可取消已有订单 |
| 管理库存与购买竞争 | 两个独立HTTP事务竞争总库存调整与购票，检查version冲突、total>=locked+sold、订单/占额/locked一致 |
| 活动下架/场次取消与下单竞争 | 按锁序串行生效，下架或取消提交后新单拒绝，最终订单数量与locked一致 |

并发通过随机端口Tomcat、真实HttpClient Cookie/Session/CSRF进入多线程服务请求和独立数据库事务，不在测试线程事务内用MockMvc模拟。测试使用独立夹具，结束后按关联范围清理，并检查竞争结束后的数据库事实；不是只判断HTTP 200。时间窗口左右边界另由既有15项销售状态单元测试精确覆盖。

回滚用例最初尝试创建临时触发器，被本机MySQL的binlog/SUPER权限限制拒绝。未提高权限、关闭binlog或跳过检查；改为只在最后一步明细Mapper注入故障，前置库存和订单SQL、数据库回滚及重试都是真实执行，修正后完整MySQL profile重跑通过。

## 浏览器真实联调

通过现有Vite代理和后端jar完成以下流程，未伪造订单数据：

1. 活动900066选择场次900040，标准票SKU900022显示100张；未登录点购票跳转登录，保留活动返回地址。
2. 使用已有数据库USER账号真实登录，返回活动；场次限额4、已占用0。选择2张，确认对话框显示¥598.00。
3. 确认创建真实订单900038（订单号cd090b8b70504d1792b3b222abe55124），详情PENDING/待支付，单价¥299.00、数量2、总额¥598.00，创建17:35、截止17:50（北京时间）。
4. 刷新页面重新恢复Session并读取同一订单；我的订单列表及详情正常。返回活动票档剩余98、已占用限额2、还可买2。直接读取MySQL同样为total=100、locked=2、sold=0、available=98。
5. 确认主动取消，详情显示已取消并提示库存/限额释放；返回原活动选场次，剩余100、已占用0、还可买4。MySQL确认订单CANCELLED、total=100、locked=0、sold=0、available=100、明细数量2保留。

证据保留在被忽略的本地 `.local/order-pending.png`、`.local/order-cancelled.png`、`.local/order-inventory-restored.png`，不提交日志、凭据或截图到仓库。

## 尚未实现与边界

没有模拟支付、自动超时关单、候补、电子票、核销、Redis预扣或异步下单。超过截止的订单不能被解释为仍可支付：服务端返回截止标志，当前没有任何支付入口；第五阶段须在支付事务拒绝超时。PENDING不会自动变EXPIRED，也不会自动释放，用户需主动取消。活动下架/场次取消不自动撤销已有订单，仍允许本人主动取消。

测试验证功能和有限并发下的数据一致性，没有吞吐压测、生产部署或高并发抢票能力结论。未来支付与后台释放不能绕过已记录的用户锁及锁序，否则当前限购证明将不再成立。
