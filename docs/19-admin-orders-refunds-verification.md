# 第十阶段订单运营与模拟退款验收

验收日期为2026年10月22日，北京时间。基于main的d959cfff05c695cc3d0bfb9cbd0c1efa627ce517实施订单运营、整单模拟退款与取消场次善后，保留单Maven模块、Session/CSRF、MySQL权威库存、同步候补/出票协作和Outbox。未接入真实资金支付或退款。

## 实际功能与核心文件

| 文件或组件 | 职责 |
| --- | --- |
| order/controller/AdminOrderController、service/AdminOrderService、mapper/AdminOrderMapper | ADMIN订单精确筛选、创建时间范围、稳定分页和只读详情；明细包含支付、候补、票券状态数量与售后记录 |
| order/service/OrderRefundService | 管理员与取消场次统一整单退款；保留支付、检查电子票、减sold、同步VOID、唯一退款记录和Outbox |
| order/service/SessionAftercareJob、mapper/AftercareMapper、config/AftercareProperties | 数据库事实候选、有界逐单独立事务、退避重试、人工待办及重启补偿 |
| order/api/OrderLifecycle、waitlist/service/WaitlistOrderLifecycle、checkin/service/TicketIssuer | 先候补关联后票券的锁内退款检查；原COMPLETED保持，全部UNUSED才允许VOID |
| ticket/api/TicketInventory、mapper/TicketSkuMapper | 带sold_stock条件的整数库存回收，检查影响行数 |
| db/migration/V6__order_refund_aftercare.sql | REFUNDED及paid_at CHECK、唯一退款流水、最小售后状态和必要查询索引 |
| frontend/src/views/AdminOrdersView.vue、components/AdminOrderDetail.vue | 运营筛选与明细、受控原因及中文二次确认；复用原样式与真实API |
| frontend/src/components/OrderCard.vue、api/orders.ts | 用户可见退款结果、原支付事实与作废票入口 |
| scripts/audit-refund-readiness.sql、audit-order-aftercare.sql | 迁移前只读预检与退款/支付/票券/库存/候补审计 |
| scripts/verify-aftercare.py | 随机标记保护的固定专用环境，调用原VerifyDatabase验证命令 |

管理端点继续位于SecurityConfig原/admin/**共用匹配器，授权前检查最新账号状态/角色；未新增鉴权旁路。写退款事务还在用户锁中核对管理员ACTIVE/ADMIN，普通买家可被禁用但系统善后仍会处理。

## 事务与库存语义

退款沿用户→活动SHARE→场次SHARE→SKU UPDATE→订单UPDATE→候补→电子票加锁。管理员与买家不同时，所有用户行先按ID递增获取，随后不再反向锁父资源。调用方代理开启READ_COMMITTED，生命周期和库存使用MANDATORY加入本事务；没有提交后异步替代票券/候补原子联动。管理员只读详情使用一致的只读快照。

仅PAID且票券数量、明细序号、用户、SKU及摘要完整、全部UNUSED时可退款。PAID→REFUNDED，sold减quantity，locked和total不变，全部UNUSED条件VOID，唯一order_id退款流水与库存事件同时提交。任何条件更新行数异常、退款流水写入故障或协作异常全部回滚。原paid_at与SUCCEEDED支付保持不变；限购统计仅PENDING/PAID，自然释放已退款数量。重复及并发退款返回同一记录，只回收一次库存。退款与核销同锁序竞争，USED不会被改写为VOID。

候补订单先检查WAITLIST_CONFIRM、waitlist-{id}、用户/SKU/数量及原COMPLETED关联；退款保留COMPLETED历史，不重新入队或转EXPIRED。Outbox新增ORDER_REFUNDED原因，载荷结构、交换机、确认、租约、重试与消费者幂等协议不变。取消场次即使数量恢复，销售与候补资格检查仍拒绝分配。

SessionService.cancel快速提交CANCELLED，不遍历全场订单。善后默认每10秒扫描至多100单，逐单独立事务；PENDING关闭释放locked，PAID调用同一退款算法。已核销、缺票、明细缺失、关联异常持久化MANUAL_REQUIRED，不猜测数量或覆盖历史。临时失败在原事务回滚后记录RETRY，默认30秒指数退避、最多5次、上限1小时，超限转人工，失败单不阻塞后单。候选、状态及重试时间来自MySQL，内存游标只用于公平扫描，启动即扫描、重启可补偿。活动OFFLINE不会触发自动退款。

## 迁移与真实数据库审计

V1至V5逐文件与起点Git内容核对不变。V6对订单状态及paid_at CHECK做同条ALTER，新增refund_record、order_aftercare、全局创建时间排序索引及取消场次状态入口；原V5候补复合外键、支付唯一键、电子票序号唯一键和库存CHECK保持。

独立新建库执行V1至V6。RefundMigrationIT另在随机schema创建V5历史已付订单、成功支付与未用票，升级V6后逐字段核对事实；故意预建同名退款表使DDL中途失败，验证已付事实、冲突表及Flyway失败记录保留，前一条ALTER可能已经提交，不宣称整个文件回滚，不自动repair或清库。异常schema留作现场核对，不操作开发库。

原专用V5库只读退款预检与候补关系审计均为0异常。浏览器独立数据最终为2笔REFUNDED、1笔CANCELLED，2条原成功支付、2条退款、2张VOID票、3条已SENT Outbox；两个SKU均locked=0、sold=0，按本次独立初始化数据核对订单数量汇总相等，所有退款一致性检查为空。实际DDL、执行计划、审计和V1至V5内容核对见[数据库结果](aftercare/results/database-audit.json)。

变更前204笔订单的计划出现全局创建时间排序及取消场次入口扫描。V6增加上述最小索引，保留变更后浏览器夹具计划。前后数据量不同，仅证明查询与索引入口，不作为吞吐或延迟优化对比；善后跨场次按订单ID排序仍可出现filesort。本阶段不宣称生产并发能力或性能提升，不覆盖第八/九阶段原始压测数据。

## 回归命令与结果

| 实际检查 | 最终结果 |
| --- | --- |
| 普通Maven clean verify | 50项基础测试，失败0、错误0、跳过0 |
| 原VerifyDatabase完整回归 | 50项基础加132项真实集成，失败0、错误0、跳过0 |
| 新增售后专项 | OrderAftercareIT 18项、V6历史升级/失败保留2项、真实应用重启补偿1项，包含在上述132项内 |
| 公开隔离脚本新建库复现 | 原完整回归再次成功，同样50加132项，不重复累加报告数量 |
| npm ci / npm run build | 安装成功、审计0漏洞；严格TypeScript及Vite生产构建通过 |
| 浏览器闭环与最终SQL审计 | 真实操作通过，退款/支付/库存/票券/候补审计0异常 |

普通后端执行`mvn -f backend/pom.xml clean verify`。前端执行`npm ci`、`npm run build`，包含严格TypeScript检查。真实集成回归使用同一Docker专用独立功能验收环境已有兼容镜像，`--pull never`，不拉取新镜像或重建卷。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/start-backend.ps1 -VerifyDatabase
```

不修改根.env，不接受调用方传入远程主机或任意库名，不清空正常ticketing_perf、开发或真实业务数据。历史升级探针只在显式_it环境运行，需专用MySQL的建schema权限。普通MySQL回归在非_it环境不会执行这两个迁移探针，应使用上述隔离入口完成本阶段验收。

最终测试数量及各类用例由实际Surefire/Failsafe XML汇总到[回归结果](aftercare/results/regression.json)。新增真实Session/MySQL测试覆盖完整退款、重复与8请求两管理员并发、退款核销竞争、支付/取消/过期竞争、事务故障回滚、票券影响行数不符、已完成候补、禁用买家、32单/4工作实例有界善后、退避与人工处理、缺失明细、活动下架不退款、过滤分页、匿名/USER/CSRF/角色撤销及金额篡改；另有V5升级/DDL失败和真实应用重启补偿。旧认证、订单、支付、FIFO、RabbitMQ确认/重试/重复投递、补偿和核销全部回归。

首轮发现未知字段被忽略，增加RefundRequest字段拒绝后真实接口测试通过。迁移历史票券比较改为递归内容比较，避免byte[]对象引用造成错误断言；仍逐字段验证内容，不省略摘要或票券检查。保留这些失败的私有日志，不将失败轮次报告为通过。

## 浏览器联调

使用真实本机前后端与独立ticketing_stage10_browser库。买家登录→活动/场次/票档→确认下单→模拟支付→管理员登录并按订单号筛选→详情核对票数→确认整单退款；切回买家看到已模拟退款及原支付流水，电子票已作废且没有二维码，刷新可恢复会话。

管理员在原场次管理中取消含待付及已付订单的场次，页面立即显示已取消，后台随后将已付单退款、待付单取消；订单运营筛选可查结果，最终SQL核对库存、退款、原支付及票券一致。普通用户直接进入/admin/orders跳转个人中心无权限提示，后端接口权限另有真实HTTP测试。[浏览器记录](aftercare/results/browser-verification.json)包含实际操作及最终数据，截图仅保存在本地忽略目录，不保存有效二维码、密码或签名密钥。

## Git 提交

业务实现提交`9b72fb0`，中文信息“实现订单运营与整单模拟退款善后”；前端提交`63870d6`，中文信息“完成订单运营页面与退款状态展示”。验收文档、开发规范与脱敏结果另作同轮文档提交。均在main，作者WGMJJM、邮箱2829827503@qq.com，只使用仓库本地Git配置；整轮验证完成后统一正常推送原origin/main，不强推，不上传根README、凭据、日志或构建产物。最终文档提交号及实际推送结果以本轮交付和Git记录为准。

## 边界

只实现整单模拟退款，未发生真实资金退回。未做部分退款、用户自助审批、人工售后强制变更、资金结算/对账、工作人员权限或Redis限流。人工待办需运营核对，系统不会覆盖已核销事实。只在独立MySQL/RabbitMQ完成迁移与联调，未向原开发或生产业务库部署V6；部署仍须先执行只读预检。完成本阶段后停止。
