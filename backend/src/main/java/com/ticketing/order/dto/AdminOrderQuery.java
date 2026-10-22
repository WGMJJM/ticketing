package com.ticketing.order.dto;

import com.ticketing.common.api.BusinessException;
import com.ticketing.order.model.OrderStatus;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import java.time.OffsetDateTime;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

public record AdminOrderQuery(@Size(max = 64, message = "订单号不能超过64位") String orderNo,
        @Positive(message = "用户编号必须为正整数") Long userId,
        @Size(max = 64, message = "用户名不能超过64位") String username,
        @Positive(message = "活动编号必须为正整数") Long activityId,
        @Positive(message = "场次编号必须为正整数") Long sessionId,
        OrderStatus status,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime createdFrom,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime createdTo,
        @Min(value = 1, message = "页码至少为1") @Max(value = 10000, message = "页码超出范围") Integer page,
        @Min(value = 1, message = "每页至少1条") @Max(value = 50, message = "每页最多50条") Integer size) {
    public int pageNumber() { return page == null ? 1 : page; }
    public int pageSize() { return size == null ? 10 : size; }
    public LocalDateTime fromUtc() { return createdFrom == null ? null : createdFrom.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime(); }
    public LocalDateTime toUtc() { return createdTo == null ? null : createdTo.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime(); }
    public void validateRange() {
        if (createdFrom != null && createdTo != null && !createdFrom.isBefore(createdTo)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "创建时间范围的结束必须晚于开始");
        }
    }
}
