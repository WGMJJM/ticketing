package com.ticketing.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

public record JoinWaitlistRequest(
        @NotNull(message="请选择票档") @Pattern(regexp="[1-9][0-9]{0,18}", message="票档编号格式不正确") String skuId,
        @NotNull(message="请填写候补数量") @Positive(message="候补数量必须大于0") Integer quantity) {
    @JsonAnySetter
    public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("候补请求含不允许的字段"); }
}
