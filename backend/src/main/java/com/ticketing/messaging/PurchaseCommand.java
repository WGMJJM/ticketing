package com.ticketing.messaging;

import com.fasterxml.jackson.annotation.JsonFormat;

/** MQ 只携带关联标识；消费参数必须从 Redis 预扣凭据取得。 */
public record PurchaseCommand(@JsonFormat(shape=JsonFormat.Shape.STRING) long requestId,
        @JsonFormat(shape=JsonFormat.Shape.STRING) long skuId) {
    public static final String ROUTING_KEY = "purchase.accepted";
}
