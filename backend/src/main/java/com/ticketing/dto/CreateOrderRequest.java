package com.ticketing.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

public record CreateOrderRequest(
        @NotNull(message = "请选择票档") @Pattern(regexp = "[1-9][0-9]{0,18}", message = "票档编号格式不正确") String skuId,
        @NotNull(message = "请填写购买数量") @Positive(message = "购买数量必须大于0") Integer quantity) {
    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("下单请求包含不允许指定的字段");
    }
}
