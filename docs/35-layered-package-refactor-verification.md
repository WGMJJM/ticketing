# 技术分层目录整理与验收

日期：2026-10-25。

按用户要求将业务域目录改为黑马点评风格的技术分层。后端仍为单Maven模块和一个Spring Boot应用，共167个生产类，已有服务使用具体类，已有协作接口继续保留。

## 目录与职责

所有目录均位于`backend/src/main/java/com/ticketing`。

| 目录 | 类数 | 示例与职责 |
| --- | ---: | --- |
| controller | 16 | ActivityController、OrderController，HTTP入口 |
| service | 29 | ActivityService、OrderService、OrderReservation，业务与事务协作 |
| mapper | 17 | ActivityMapper、OrderMapper，MyBatis数据访问 |
| entity | 12 | Activity、TicketOrder、PurchaseRequest，数据库记录 |
| dto | 15 | CreateOrderRequest、AdminOrderQuery、TradeContext，入参及内部快照 |
| vo | 19 | OrderView、PurchaseRequestView，响应视图 |
| config | 17 | SecurityConfig、OrderProperties，Spring装配与属性绑定 |
| enums | 14 | OrderStatus、SaleStatus，业务状态和枚举 |
| security | 6 | AccountPrincipal、CurrentAccountFilter、TicketCredentialSigner |
| task | 7 | PurchaseRecoveryJob、OrderExpiryJob、WaitlistContinuationScheduler |
| messaging | 8 | PurchaseOrderConsumer、OutboxPublisher、InventoryEvent |
| cache | 1 | CatalogCache，公开目录缓存 |
| exception | 2 | BusinessException、ApiExceptionHandler |
| common | 3 | ApiResponse、PageResponse、SystemStatusResponse |
| 根包 | 1 | TicketingApplication，唯一启动类 |

例如原`trade/OrderController.java`迁至`controller/OrderController.java`，原`trade/OrderMapper.java`迁至`mapper/OrderMapper.java`，原`trade/TicketOrder.java`迁至`entity/TicketOrder.java`。相关测试、显式全限定名和原通配符导入同步更新；测试中的appconfig/model/waitlist目录调整至config/enums/task。

## 架构约束

`ArchitectureRulesTest`和`architecture-modules.properties`分别表达技术层与逻辑业务归属。每个生产类必须完整登记，业务依赖继续无环；候补、核销仍只能使用交易公开协作契约。

分层规则要求Controller不依赖Mapper或实体，Service不依赖Controller，Mapper及数据类型不依赖业务服务、任务、消息处理或缓存。保留既有`UserView.from(AccountPrincipal)`会话身份投影的精确例外。

逐类机械核对167个生产类的实现体，除包引用与空行外一致。HTTP路径、SQL、事务传播、锁顺序、重试、幂等及后台任务配置保持原有实现；V1至V10迁移、前端和性能默认配置没有改动。本轮为目录整理，不产生新的吞吐或延迟结论。

## 验证

普通验证：`mvn -f backend/pom.xml clean verify`，90项通过，失败、错误、跳过均为0。

完整集成验证使用`python -u -B scripts/performance/verify_backend.py --label layer_refactor_1025`，固定ticketing-perf容器、随机guard及独立随机schema。2026-10-25 20:23:55完成，Maven耗时6分47秒，257项通过，失败、错误、跳过均为0。脚本退出码0，成功清理本轮13个符合规则的隔离schema。

两类测试共347项通过。集成回归包含Spring装配及配置绑定、真实HTTP认证与CSRF、MyBatis数据访问、异步购票17项、候补与消息联动29项，以及订单、支付、退款、出票、核销、库存、迁移、重启补偿和用户场次限额。Java包引用残留扫描及`git diff --check`通过。

历史性能报告和原始结果保留在原路径。
