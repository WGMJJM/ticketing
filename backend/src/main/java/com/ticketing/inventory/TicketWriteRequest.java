package com.ticketing.inventory;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

public record TicketWriteRequest(
        @NotBlank(message = "请填写票档名称") @Size(max = 100, message = "票档名称不能超过100字") String name,
        @NotNull(message = "请填写票价") @Pattern(regexp = "(?:0|[1-9][0-9]{0,18})", message = "票价必须为非负整数分") String priceCents,
        @NotNull(message = "请填写总库存") @PositiveOrZero(message = "总库存不能为负数") Integer totalStock,
        @NotNull(message = "请填写开售时间") OffsetDateTime saleStartsAt,
        @NotNull(message = "请填写停售时间") OffsetDateTime saleEndsAt,
        @NotNull(message = "请填写单笔限购数量") @Positive(message = "单笔限购数量必须大于0") Integer maxQuantityPerOrder,
        @PositiveOrZero(message = "版本号不能为负数") Integer version) {

    @AssertTrue(message = "停售时间必须晚于开售时间")
    public boolean isSaleWindowValid() {
        return saleStartsAt == null || saleEndsAt == null || saleEndsAt.isAfter(saleStartsAt);
    }

    // 请求不能夹带 lockedStock、soldStock、status 或所属场次等只读字段。
    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("票档请求包含不允许修改的字段");
    }
}
