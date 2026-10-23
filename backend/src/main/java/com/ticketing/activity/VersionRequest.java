package com.ticketing.activity;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record VersionRequest(@NotNull(message = "缺少数据版本，请刷新页面")
                             @PositiveOrZero(message = "版本号不能为负数") Integer version) {
}
