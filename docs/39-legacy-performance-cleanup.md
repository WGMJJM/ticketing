# 旧性能体系与本地临时文件清理

2026年10月26日，按用户要求直接删除旧负载工具和历史结果，随后进一步清理整个`.local`，不新增备份或归档。本轮未实现JMeter，也未重新生成性能结果。

## 已清理内容

- 删除旧性能文档目录和旧性能脚本目录，包括负载脚本、Python调度/分析、历史JSON/SVG/CSV类结果、截图、工具安装器、专用Compose及应用配置。
- 删除架构结果中的旧性能总表，以及仅用于旧测量的机器、源码清单、运行参数、JFR/长稳及传输分析结果。功能、迁移、数据库审计和浏览器断言记录保留，移除其中失效路径与性能专用字段。
- 删除两份显式执行的旧Java微基准`AdmissionCapacityBenchmark`和`TradeKernelBenchmark`；普通单元、集成、架构及Playwright业务测试保留。
- 混合文档只清理旧测量章节、数字表格和执行命令；Outbox、库存预扣、购票恢复等业务说明继续保留。三份混合历史报告整理为对应的业务说明，未整篇删除业务内容。
- 必要的功能验收入口、浏览器夹具及四份业务审计SQL提取至`scripts/verification/`，两项既有Python依赖移至`scripts/requirements.txt`。不保留负载生成、性能报告、故障发压或环境自动管理功能。

活动源码、业务Lua、Flyway迁移、认证注册、缓存、限流、异步购票、Outbox、订单、候补、退款和核销实现均未修改。业务Compose、部署配置、`.env.example`及本机`.env`保持原值。根README只在本地修正入口，继续忽略、不提交。

## Docker与业务数据

根据Compose项目标签、挂载和网络成员核对后，实际删除：

- 容器`ticketing-perf-mysql-1`、`ticketing-perf-redis-1`、`ticketing-perf-rabbitmq-1`。
- 数据卷`ticketing-perf_perf_mysql`、`ticketing-perf_perf_rabbit`。
- 网络`ticketing-perf_default`。

当前业务的3个容器、`ticketing_mysql_data`、`ticketing_redis_data`、`ticketing_rabbitmq_data`及`ticketing_default`均保留。兼容镜像由业务共用，未删除或重新拉取；其他项目资源未操作，未执行全局prune。

业务MySQL卷内还存在5个旧功能/迁移验证schema，包含失败迁移现场；它们不是本次能够认定为旧负载专用的数据，故未猜测性删除。`ticketing`库账号、活动、订单行数仍为3、8、6，没有执行业务库DDL或表级数据清理。

## `.local`补充清理

此前已经删除旧负载工具及专用卷的本地归档；用户再次要求清理全部无用本地文件后，又删除1,799个旧阶段文件及216个空目录，包括临时JAR、导出源码、截图、日志、测试结果和备份。

补充清理前约1.05GiB，清理后不足0.2MiB，只有服务日志会随运行增长。只剩`runtime`目录内当前服务实际使用的启动脚本、两个PID文件和四个日志文件，共7个文件。未新增备份，不保留清理过程的临时清单、截图或报告文件。

## 基础验证与后续边界

业务及既有测试源码编译`mvn -f backend/pom.xml test-compile -DskipTests`通过；前端`npm run build`通过。Compose配置检查、Python/JavaScript语法、文档JSON解析和本地Markdown链接检查通过；无失效的旧负载执行入口。保留的MySQL内部schema名、Java功能测试guard契约及历史功能验收环境标记不构成旧工具依赖。

实际部署只读检查通过，清理后后端健康为UP，前端HTTP为200。没有新增或执行单元、集成、端到端或性能测试，没有生成新的性能结果。

后续接入JMeter只需另行建立一个订单创建`.jmx`计划、一份不含凭据的参数示例和简短执行说明。成功订单TPS须以最终成功创建的订单计数，HTTP202受理不能算成功订单；订单创建P95的计时起止需在后续方案中明确。本轮不创建这些文件。
