# HTTP API 契约

## 已实现

`GET /api/v1/system/status`，匿名可访问，HTTP 200：

```json
{"code":"OK","message":"操作成功","data":{"application":"票务交易平台","status":"RUNNING"}}
```

该接口只表示后端 HTTP 可访问。`GET /actuator/health` 返回标准 Actuator 聚合健康，健康时 `{"status":"UP"}`，详情不公开；`GET /actuator/info` 已开放，当前无额外信息。其余业务 API 拒绝访问，不存在占位成功接口。当前 Security 拒绝请求为 403，框架错误不承诺 ApiResponse 包装，不返回内部异常信息。

## 后续草案，全部待开发

统一前缀 `/api/v1`，成功继续使用 `ApiResponse<T>`；参数错误 400、未登录 401、无权限 403、资源不存在 404、状态或幂等冲突 409。后续实现统一中文错误契约，不能将下述草案当成现有端点。

| 领域 | 用户 API 草案 | 请求与主要响应字段 | 权限与语义 |
| --- | --- | --- | --- |
| auth | POST `/auth/register`、POST `/auth/login`、POST `/auth/logout`、GET `/auth/me` | username/password；用户 id、username、role | 注册与登录公开；其余认证；不返回哈希 |
| event | GET `/activities`、GET `/activities/{id}`、GET `/activities/{id}/sessions` | city/category/page/size；标题、场馆、场次时间 | 仅公开已发布活动 |
| ticket | GET `/sessions/{id}/skus` | SKU id、name、priceCents、saleStartsAt、saleEndsAt、availability | 公开查询，不承诺查询时的库存等于下单结果 |
| order | POST `/orders`、GET `/orders/{id}`、GET `/orders`、POST `/orders/{id}/cancel` | 创建仅 skuId、quantity 和 Idempotency-Key；响应订单号、状态、totalAmountCents、expiresAt | 必须认证，校验归属；价格由服务端计算 |
| order | POST `/orders/{id}/simulated-payment` | 幂等键；流水号、订单状态、paidAt | 用户本人，模拟支付，金额由订单事实决定 |
| waitlist | POST `/waitlist`、GET `/waitlist`、POST `/waitlist/{id}/cancel`、POST `/waitlist/{id}/confirm` | skuId、quantity、幂等键；状态、排队顺序、reservedOrderId、截止时间 | 用户本人；确认不能客户端改价 |
| checkin | GET `/tickets`、GET `/tickets/{id}` | 票号、场次、状态、认证后获取的签名凭证 | 仅持票本人；凭证响应禁止公共缓存 |

管理草案：`/admin/activities`、`/admin/activities/{id}/sessions`、`/admin/sessions/{id}/skus` 的创建与更新；`POST /admin/skus/{id}/stock-adjustments` 只能做有审计依据的库存调整，不能改坏库存不变量；`POST /admin/checkins` 接收票券凭证并原子核销，`GET /admin/checkins` 查询核销记录。仅 ADMIN/后续细化的工作人员权限，不因前端隐藏按钮而省略服务端校验。管理审计详情表在实现时新增迁移。

所有资源 ID 为字符串，quantity 为正整数；后端按业务上限验证。金额分以十进制整数字符串返回，业务代码用整数计算。日期带时区，例如 `2030-01-01T10:00:00Z`。分页返回 `items/page/size/total`，总数按长整数策略传输。

下单幂等由服务端认证用户+操作域+请求键定义，同参重试返回原结果，异参同键返回 `IDEMPOTENCY_CONFLICT`（409）。操作参数先规范化再计算摘要，最终由数据库唯一键保障。同一个 MySQL 事务完成订单、库存预占与 Outbox；支付和超时关单通过状态条件更新竞争，业务成功不以 MQ 发出为准。

候补先保证同票档队列内部的 FIFO 策略，默认队首数量不满足时不跳过；相对新订单的严格优先级暂不承诺。RabbitMQ 用于触发候补和业务事件，配合 Outbox、重试、幂等消费及定时扫描恢复；核销最多一次成功必须由数据库条件更新保证。
