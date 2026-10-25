package com.ticketing.dto;

import com.ticketing.enums.AuditAction;
import com.ticketing.exception.BusinessException;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

public record AuditQuery(
        @Positive(message = "操作人编号必须为正整数") Long operatorId,
        AuditAction action,
        @Pattern(regexp = "ACTIVITY|SESSION|TICKET_SKU|STAFF|ORDER", message = "资源类型不正确")
                String resourceType,
        @Positive(message = "资源编号必须为正整数") Long resourceId,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
        @Min(value = 1, message = "页码至少为1") @Max(value = 10000, message = "页码超出范围") Integer page,
        @Min(value = 1, message = "每页至少1条") @Max(value = 50, message = "每页最多50条") Integer size) {
    public int pageNumber() {
        return page == null ? 1 : page;
    }

    public int pageSize() {
        return size == null ? 10 : size;
    }

    public LocalDateTime fromUtc() {
        return from == null ? null : from.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    public LocalDateTime toUtc() {
        return to == null ? null : to.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    public void validateRange() {
        if (from != null && to != null && !from.isBefore(to)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "结束时间必须晚于开始时间");
        }
    }
}
