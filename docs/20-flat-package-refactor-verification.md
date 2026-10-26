# 后端扁平目录整理验收

验收日期为2026-10-22，整理前基准为`db8ed21d39bcb02e07b9c427f4f886c8dc2713ab`，在现有`main`完成。按用户明确选择，controller、service、mapper各只有一个目录，类文件直接放入，不再建立业务子目录。保留原有类与职责，未把所有业务合并成一个Controller、Service或Mapper。

## 当前目录与迁移范围

根目录为`backend/src/main/java/com/ticketing`，启动类仍是`TicketingApplication`。下表中的目录均直接包含Java类，不再嵌套业务目录。

| 目录 | 生产类数量 | 内容示例 |
| --- | ---: | --- |
| controller | 13 | AuthController、ActivityController、OrderController、AdminOrderController、ApiExceptionHandler |
| service | 19 | OrderService、OrderStateService、OrderReservation、TradeResourceLocker、WaitlistService、OrderRefundService |
| mapper | 12 | UserAccountMapper、TicketSkuMapper、OrderMapper、OutboxMapper、RefundMapper |
| dto | 27 | 请求、视图、分页投影 |
| entity | 9 | 数据库实体 |
| model | 15 | 状态、操作域、TradeContext、TicketSale |
| config | 9 | Spring配置、配置属性、管理员初始化 |
| security | 3 | AccountPrincipal、CurrentAccountFilter、TicketCredentialSigner |
| job | 5 | 订单过期、场次善后、历史补票、Outbox发布、候补补偿 |
| messaging | 4 | InventoryEvent、InventoryEvents、OutboxPublisher、InventoryConsumer |
| api | 2 | OrderLifecycle、SessionWindowGuard同步协作契约 |
| common | 3 | ApiResponse、PageResponse、BusinessException |

共122个生产源码文件，与整理前相同；22个测试源码文件同步调整包引用和必要路径。旧业务包对应的空目录已移除，源码、测试、配置及脚本中没有旧业务全限定类名残留。历史阶段验收报告保留当时的路径及原始结果，不把历史事实批量改写为新路径。

## 业务保持与依赖检查

仅变更Java包声明、import和必要的全限定类名。逐类从基准提交读取原始源码，映射类名后去掉package/import及空行，精确比较剩余源码，保留注解、SQL、字符串和行内空白：122类全部一致。V1～V6逐字节一致，未新增迁移。结果见[源码与迁移审计](engineering/results/flat-package-source-audit.json)。

`@SpringBootApplication`、`@ConfigurationPropertiesScan`仍从`com.ticketing`扫描，Mapper继续使用原`@Mapper`发现机制，不需要增加扫描配置或改变Bean装配方式。HTTP接口、认证、CSRF、状态值、属性名称及环境变量保持不变。

物理目录改为技术分层，原业务归属通过测试资源`architecture-modules.properties`逐类登记，不加入运行时框架。`ArchitectureRulesTest`检查所有生产类登记完整、技术目录无嵌套、业务依赖无环、订单不反向依赖候补/核销实现，以及候补/核销不得访问订单内部Mapper、实体和非公开服务。检查同时识别全限定引用和同包省略import的类引用，避免目录合并后漏检。原公开协作OrderReservation、TradeResourceLocker及契约/模型/DTO仍可使用。

事务入口、传播方式、条件库存SQL及用户→活动→场次→SKU→订单→候补→电子票锁序未变。同步候补联动、出票、退款与库存继续共用MySQL事务；Outbox租约、确认、退避、补偿和幂等机制保持原实现。

## 实际构建与回归结果

| 检查 | 真实结果 |
| --- | --- |
| `mvn -f backend/pom.xml clean verify` | 成功，51项基础测试，0失败、0错误、0跳过；目录与依赖守卫2项 |
| `python scripts/verify-aftercare.py` | 核验固定独立环境随机标记后调用原`start-backend.ps1 -VerifyDatabase`，51项基础及132项真实MySQL/RabbitMQ集成测试全部通过；总计183项，无失败或跳过 |
| `cd frontend; npm ci` | 成功，143个依赖包安装，依赖审计0项漏洞 |
| `cd frontend; npm run build` | 严格TypeScript检查和Vite生产构建成功，前端源码无变更 |
| 新构建后端启动 | 复制构建jar到忽略目录启动，MySQL、RabbitMQ连接成功，真实HTTP及页面可访问 |

集成测试在当时的独立验收环境（现已退役）实例的独立`ticketing_perf_aftercare_it`执行；迁移测试使用其原有随机测试schema，新建、历史升级及异常数据迁移用例通过。没有对开发库或历史业务库清理、迁移或注入故障。各测试套件数量及结果见[回归统计](engineering/results/flat-package-regression.json)。

本轮不重复性能压测，也不声称目录调整带来吞吐量提升。旧性能基线与原始统计已按最新清理要求移除。

## 浏览器真实联调与数据库事实

使用新构建后端、现有Vue页面及独立`ticketing_stage10_browser`，通过真实管理员Session与CSRF准备新的回归活动、场次及票档。浏览器完成普通用户登录、刷新恢复、查询场次/票档、创建真实订单、模拟支付、退出及管理员登录、订单运营查询和整单模拟退款。支付后运营页面显示1张未使用电子票；退款后显示1张已作废票。没有展示或导出有效票券凭证。

最终数据库核对：该票档总库存8、锁定0、已售0；本次订单为REFUNDED且保留原paid_at；成功支付记录1条、成功模拟退款记录1条，金额均为12800分；VOID票1张；库存Outbox事件1条已SENT。最终事实见[浏览器数据库审计](engineering/results/flat-package-browser-audit.json)。

`AGENTS.md`及架构、API、数据库、开发计划、本地开发说明已更新当前目录约定。根README继续仅本地保留，凭据、日志、截图、jar与构建产物均在忽略目录，不纳入Git。
