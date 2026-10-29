# 普通异步建单减少两次查询

2026-10-29，按用户要求审查并优化普通建单的重复幂等查询和成功终态回读。每笔正常普通建单减少两次SELECT，未增加数据库表、迁移、库存桶或消息链路，业务默认配置不变；本次没有复测TPS，不据SQL数量推算吞吐增幅。

| 位置 | 调整 | 保护条件 |
|---|---|---|
| 建单前订单幂等查重 | 普通CREATE_ORDER经消费者协调后调用reserveCoordinatedPurchase，直接使用已核对申请的摘要、幂等键和requestId | 仍先coordinate并锁定申请，逐项核对用户、票档、数量、操作域、候补关联及摘要；终态直接返回 |
| finishSuccess条件UPDATE后 | 从已验证申请和orderId构造成功结果，取消紧接着的find | 原状态及数据库截止条件UPDATE不变，失败仍抛错并整笔回滚；SQL提交后才能结算Redis |

第一处位于首次桶X锁之前，减少整个事务的SQL往返；第二处位于事务尾部，直接减少桶X锁持有期间的一次查询。额度、金额快照、库存条件更新、allocation、锁序和回滚后跨桶重试保持原实现。

## 幂等和历史兼容

V8已将历史CREATE_ORDER订单回填为SUCCEEDED申请，V14迁移要求旧未决申请排空且保留终态；合法历史重放和重复消费由申请事实返回原订单。原reservePurchase重载继续查询旧订单并比较摘要，包括已取消订单；候补继续使用该入口。ticket_order和purchase_request唯一约束保留，不捕获事务内唯一键异常后返回成功；异常数据或唯一键冲突会回滚订单、申请、库存及额度，保留Redis预扣供恢复处理。

成功结果保留原申请标识、用户、SKU、数量、摘要、幂等域、候补关联和受理/截止时间，设置SUCCEEDED、orderId及空失败码。仅供当前事务参与者和提交后Redis结算使用，updatedAt明确为null，不伪造数据库更新时间。已核对WaitlistPurchaseLifecycle、Redis结算、历史回填及对外View均不依赖它；数据库仍由原UPDATE写真实UTC时间，详情查询继续读取完整数据库记录。失败路径的回读没有改动。

## 实际验证

- clean verify成功，115项普通测试通过，包含新增3项旧内部入口/候补幂等测试及架构规则。
- 11项真实MySQL/Redis/RabbitMQ针对性场景通过：新增正常路径SQL调用计数、真实订单唯一键冲突回滚；既有并发重放、重复消息、写入/结算故障恢复、成功UPDATE失败、截止时间竞争、FIFO候补、八桶回退、历史allocation履约和最终事务失败。
- coordinatedCreateSkipsOrderLookupAndSuccessReadback验证：正常普通建单不调用OrderMapper.selectOne，PurchaseRequestMapper.find仅有事务外终态短路的一次；数据库成功状态及更新时间、Redis终态正确，重投不再建单。
- uniqueOrderViolationRollsBackWithoutSettlingTheReservation制造真实uk_order_idempotency冲突，验证异常离开事务后申请及新增库存/额度全部回滚，预扣仍QUEUED，恢复后恰好建单一次。

沿用现有13307/16379/15673专用容器，核验标签、卷与原guard后，在随机ticketing_sql_reads_49d92e4e4501_it库及临时performance_control guard执行；未覆盖指向旧环境的.local/verification/environment.json，也未操作业务库或已有压测数据。首次验收因临时缓存命名空间过长未启动，修正后又发现5个损坏的target class产物；clean重新编译后全部class头正常，上述115+11项在同一轮验证全部通过。成功后已精确删除临时SQL库/guard、命名空间Redis键、4个测试MQ资源及2条可确认的夹具消息，原压测队列为空。最小验收摘要位于.local/verification/purchase-sql-read-checks/validation-summary.json。

本机Docker中途无法启动，日志定位为Windows AF_UNIX运行文件访问失败；重建Docker/run及docker-secrets-engine临时目录，并将EnableDockerAI设为false后恢复，三个原隔离容器健康，镜像/数据卷保持原样。被Windows拒绝访问的旧套接字目录已移开，仅含零字节运行文件，未作数据备份。此项仅为本机恢复操作，不进入项目配置。
