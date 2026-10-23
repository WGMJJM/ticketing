package com.ticketing.trade;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotNull;

public record RefundRequest(@NotNull(message = "请选择模拟退款原因") RefundReason reason) {
    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("模拟退款不接受客户端指定金额、身份或其他业务字段");
    }
}
