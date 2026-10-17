package com.ticketing.event.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

public record SessionWriteRequest(
        @NotBlank(message = "请填写场次名称") @Size(max = 100, message = "场次名称不能超过100字") String title,
        @NotNull(message = "请选择开始时间") OffsetDateTime startsAt,
        @NotNull(message = "请选择结束时间") OffsetDateTime endsAt,
        @NotNull(message = "请选择开售时间") OffsetDateTime saleStartsAt,
        @PositiveOrZero(message = "版本号不能为负数") Integer version) {

    @AssertTrue(message = "结束时间必须晚于开始时间，开售时间必须早于开始时间")
    public boolean isTimeRangeValid() {
        return startsAt == null || endsAt == null || saleStartsAt == null
                || (endsAt.isAfter(startsAt) && saleStartsAt.isBefore(startsAt));
    }
}
