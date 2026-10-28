# 购票接口 HTTP QPS 与 P99 专项结果

2026-10-28，按用户补充要求直接执行100线程，复用8000个有效且仍有场次额度的旧账号预登录，同一轮每笔请求使用不同用户、新幂等键，数量1。没有再创建账号或重置领取游标。用户允许旧账号的补充要求覆盖原提示词的未领取账号约束。

- 购票接口 HTTP QPS：**100.90 请求/秒**，100线程，**仅5.114秒中断前观测，无有效30秒稳定窗口**。
- 购票接口成功受理 HTTP P99：**2427 ms**，只使用上述窗口内完整响应完成的202采样，排除脚本等待和订单结算耗时。

观测窗口为 `2026-10-28T22:48:25.232+08:00` 至 `2026-10-28T22:48:30.346+08:00`，按HTTP完整响应完成时间归档、左闭右开。窗口内516个响应，202占比100%，429/503/网络失败均0。停发后仍有63个已发HTTP请求完成，整轮共579个202；这些晚完成响应没有计入上述短窗口QPS。停发后新增POST为0，账号和幂等键没有重复。

预热期间未完成预扣增长至脚本500门禁即停止新请求，尚未达到30秒预热稳定要求，正式测量未开始。本轮不能证明100线程持续受理能力，不能把该数字写成简历的稳定QPS，也未挑战更高负载。

环境为既有`ticketing-jmeter`隔离项目、8桶SKU5、12消费者/prefetch1、Hikari16、发布确认窗口256、购票容量1000，命令超时1秒。脚本500门禁早于原750保护线，没有放大Redis容量、关闭消费者或调整业务规则。CPU监控峰值63.41%，可用物理内存最低15.09GiB。保护由容量门禁触发。

579个202最终均已形成成功终态，MQ ready/unacked、Redis pending/recovery均归零。初次审计时库存通知尚有12条PENDING；正常关闭期间已全部发送，只读复核通过，最近100条库存通知均为历史SKU2且已SENT。初次`final-audit.json`保留原失败事实，最终核验见`after-drain-audit.json`；没有删除事件、手动改库存或重新发压。

## 文件和复现

新增`purchase-http-qps.py`、`purchase-http-qps.jmx`、`http_qps_stats.py`、`test_http_qps_stats.py`和[专项说明](../../scripts/jmeter/HTTP_QPS.md)，更新JMeter README。当前原有完整建单诊断入口与业务源码均未改动。

可直接运行的PowerShell命令见[专项说明](../../scripts/jmeter/HTTP_QPS.md)。再次执行仍受相同容量、资源、账号资格与队列保护，不保证得到完整窗口。新增8项统计检查和原15项工具检查共23项通过；Python语法、JMX XML和库存通知只读等待检查通过。没有为本任务重跑后端全量回归。

证据位于本机`.local/jmeter/redis-mysql-optimizations-20261028/http-qps-e80d3f94/`：

- `source.json`：实际运行的脚本SHA、Git版本与隔离卷身份。
- `runtime-proof.json`：后端实际参数、PID与隔离配置。
- `selftest/`：5笔真实请求和自动停发校验。
- `threads-100/post.jtl`：逐笔原始HTTP采样。
- `threads-100/run.json`、`summary.json`、`stop.flag`、`resources.jsonl`：窗口、结果、门禁与资源现场。
- `threads-100/final-audit.json`、`after-drain-audit.json`及根目录`shutdown.json`：初次与最终核验、正常关闭证明。

会话Cookie/CSRF文件已删除，异常现场和原始JTL保留。验收后仅补齐停发后等待正常库存通知发送和中断观测输出，未重复100线程发压。
