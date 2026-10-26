# 第十六阶段后关键链路审查与整改

状态：整改及本地验收完成。起点`ff1c1650de30b317ebfe17bdb5cf9c86b648b9be`，开始时main工作区干净（2026年10月24日）。本次授权来源为用户确认执行的`ticketing_latest_audit_and_fix_prompt.md`，不是第十六阶段性能结论的追认。本文按调查顺序保留实验，**最终选择为窗口16流水线发布、逐条确认条件落账；批量SENT实验已回退**。最终选择及限制以文末为准。

## 初始代码审查结果

下表为修复前代码定位；性能归因和未证实风险须经过隔离实验后更新，不能由静态阅读推断负载能力。

| 编号 | 分类 | 最小复现条件与定位 | 处理与验证 |
| --- | --- | --- | --- |
| A1 | 已证实性能设计缺陷 | `messaging/OutboxPublisher.java:43`每条认领、发送后在`:90`阻塞等待Confirm，下一条尚未发送；队列持续受理时累加确认往返 | 保留旧JAR与同场景基线，比较有界在途窗口；ACK与Return、认领令牌及租约不变 |
| A2 | 未证实风险 | `admission/PurchaseRequestMapper.java:30`单行持久预算，成功及失败释放同一行；此前两个时刻见过锁等待，不足以证明所有负载的瓶颈 | 区分账号、预算、票档与连接池等待；先测量，必要时才增量迁移，要求一万个独立隔离账号 |
| A3 | 已证实设计缺陷 | `admission/PurchaseEntryService.java:18`查重后只有速率准入；`PurchaseAdmission.java:41`直接写受理，非存在或明确停售的新票档也写请求、预算与Outbox | 非事务入口用实时轻量读预检，旧键回放优先；消费者最终权威校验保留 |
| A4 | 预期业务行为 | `PurchaseAdmission.java:31`仍锁账号UPDATE；交易SHARE只改变交易侧，不代表HTTP受理全并行 | 保留幂等与预算原子性，如无证据不重写账号协议 |
| A5 | 已证实诊断缺陷 | `messaging/OutboxPublisher.java:72`购票命令失败也称库存事件，日志输出事件标识且缺少分通道确认指标 | 按低基数消息通道及结果计量，不把业务标识作标签，不记录载荷和凭据 |
| A6 | 已证实体验缺陷 | `auth/RequestRateLimitFilter.java:95`在业务入口查重前返回429或安全故障503；客户端若丢失首次响应，重放也暂时受限 | 不绕过安全过滤器，提供当前用户按幂等键只读查找与有限恢复，测试越权和真实429 |
| A7 | 预期业务不确定性 | `trade/OrderService.java:32`取消已提交后再组装详情，后续读失败可能使响应失败而订单已取消 | 保持短事务，验证同键语义下重复取消及状态查询恢复，完善客户端提示 |
| A8 | 未证实风险 | 额度、候补、模拟退款、核销、权限撤销及缓存的并发路径；306项通过只证明既有覆盖 | 逐项复核与新故障回归，不无依据改写已正确实现 |
| A9 | 预期部署门禁 | 开发库最近只读记录为V7，V8/V9未部署；最新代码不直接连接旧库运行 | 全部实验当时的独立验收环境（现已退役）与随机guard；V1～V9字节冻结，开发库不迁移、不清理、不repair |

## 业务正确性审查结论

| 审查对象 | 本次结论及验证边界 |
| --- | --- |
| 交易额度与库存 | 保留账号SHARE→活动→场次→额度UPDATE→票档→订单→候补→票券顺序；真实并发及双向SUM审计覆盖，释放不足回滚。未复现超卖、负额度或重复释放缺陷 |
| 受理容量 | 服务层及真实一万独立Session专项测到单行热点；没有证据证明分片改善当前完整链路，未新增V10、分片或容量归属字段 |
| 无效新申请 | 既存键优先回放，新键以实时主键点查拒绝不存在/停售/下架/窗口外票档；短只读事务归还连接后才做Redis与写受理。预检不占库存，消费者权威校验保留 |
| 重放与安全 | 新GET按可信当前用户及CREATE_ORDER键查找，no-store；授权前数据库复查沿用SecurityConfig共用匹配器。真实429不阻止只读恢复，也不绕过POST过滤器；越权不存在返回404 |
| 候补、出票及退款 | 同事务生命周期及原FIFO保持；暂停、重复投递、支付/取消/过期、退款与核销竞争复用原回归。未改写COMPLETED候补或既有支付/核销历史 |
| 取消响应不确定性 | 保留提交后独立详情读取；新增故障回归确认取消已经提交且重复取消安全。前端查询取消状态后明确告知，仍未知时保留安全重试提示 |
| 缓存、权限及凭证 | 正负缓存、提交后失效、Redis故障回源、会话停用与撤权竞争沿用真实回归；不更改凭证签名、摘要、存储或权限语义 |

未发现的缺陷不等于不存在；账号受理串行、容量行热点及不稳定长尾是仍需在目标负载下评估的限制。没有本次部署开发库、真实资金渠道或大规模生产容量验证。

## 文件与SQL变更清单

生产类共10个：`messaging`中的OutboxPublisher、OutboxService、OutboxMapper、OutboxProperties；`admission`中的PurchaseAdmission、PurchaseEntryService、PurchaseProcessing、PurchaseRequestsController；`inventory`中的TicketInventory、TicketSkuMapper。没有新增生产类或启动服务。

SQL修改仅为OutboxMapper有界批量SKIP LOCKED候选读取及认领UPDATE、TicketSkuMapper实时销售预检SELECT；逐条SENT/失败CAS继续按id、状态及递增attempt校验。生产无新增表、索引或迁移。V1～V9逐字节未变，详见[历史迁移校验](architecture/results/postaudit-historical-migrations.json)。

前端修改`api/orders.ts`、`components/PurchasePanel.vue`、`views/OrdersView.vue`，实现本人按键恢复及取消状态恢复。中断前最后一处提示编辑把变量放错catch作用域，续接时已纠正并重新通过严格TypeScript及生产构建。

测试修改ConfigurationBindingTest、RateLimitIT、UserSessionQuotaIT、AsyncPurchaseIT、OutboxPublisherTest、WaitlistManagementIT；新增OutboxPipelineIT及显式运行的AdmissionCapacityBenchmark。后者不计入普通或mysql-it默认回归数量，不默认制造一万账号。

未改生产边界：交易锁序/订单状态服务/额度SQL、模拟支付与退款、核销与出票、候补生命周期、Session/CSRF/安全限流、缓存实现、受理预算模型、数据库迁移及Docker固定拓扑。没有引入微服务、多实例、真实支付退款或Redis库存。

## 最终交付验证

回退无收益的批量SENT后再次完整执行隔离`mvn clean verify -Pmysql-it`，普通87项、真实集成231项，共318项，失败/错误/跳过均为0。包含真实缺交换机NACK与另一通道健康消息并存、行锁超时、池耗尽、取消提交后读取失败等新增场景。重新构建JAR的所有应用条目与最终选择产物逐字节相同；[最终测试及源码摘要](architecture/results/postaudit-delivery-validation.json)独立记录，不覆盖此前318/320项实验报告。前端严格TypeScript与构建通过；压测隔离单元检查7项及登录状态检查4项通过。

真实Redis停机、L1过期、20并发回源、恢复重新填充等[11项检查](architecture/results/catalog-faults-postaudit-delivery.json)通过，追加验证真实Redis故障时POST仍503关闭、本人GET原键仍200找回且no-store。异步[8项故障检查](architecture/results/async-faults-postaudit-delivery.json)通过，包含应用处理中中止、代理持续停机后过期释放、数据库真实停机响应ReadTimeout后同键恢复只建一单；不把超时当作已受理或成交。

浏览器[14项联调与最终数据库事实](architecture/results/postaudit-delivery-browser.json)通过：首次受理202响应及取消200响应均在后端完成后由隔离代理延迟7秒，触发前端5秒超时；页面分别自动找回同一订单、查询确认已取消。停售409明确提示未受理，恢复开售后原键可首次受理；真实安全429后GET404不新增申请，刷新保留原参数，等待窗口结束后原键成功。随后完成模拟支付、唯一出票、管理员整单模拟退款和买家重新登录回看。最终三笔申请全部SUCCEEDED、两单CANCELLED/一单REFUNDED，成功支付事实与支付/退款流水各保留一份，唯一电子票VOID，两票档locked/sold均0、可售均20，场次额度0；完整一致性审计通过。

联调截图：[首次响应恢复](images/postaudit-create-recovered.png)、[取消超时恢复](images/postaudit-cancel-recovered.png)、[停售拒绝保留参数](images/postaudit-unavailable-retained.png)、[真实429](images/postaudit-rate-limit-retained.png)、[退款保留支付事实](images/postaudit-refunded.png)、[额度及库存释放](images/postaudit-quota-released.png)。均为随机非实名隔离账号，不包含密码、Cookie或完整电子票凭证。

## 环境保全与部署边界

本轮结束后停止单个专用应用、浏览器代理与ticketing-perf三个容器，保留所有Docker卷及原失败schema。专用MySQL全部44个非系统schema均已导出到忽略的.local，校验mysqldump完成标记、SHA-256与gzip解压逐字节一致；[脱敏备份清单](architecture/results/postaudit-delivery-backups.json)不含SQL或秘密。本次没有执行备份恢复演练，不将解压校验称为已验证恢复。

原开发库仍V7，其[只读审计](architecture/results/postaudit-development-readonly.json)通过并恢复原停止状态；V8/V9未部署，未新增V10，历史迁移未改。后续部署必须停写/停消费者、备份及可恢复性验证、依次执行候补/退款/审计/受理/额度只读预检、人工单次批准、逐版本迁移与同快照后验对账后再放写；V9批准开关完成后撤销。异常只报告并停止，不repair、不清库。代码交付不等于开发库已升级。
