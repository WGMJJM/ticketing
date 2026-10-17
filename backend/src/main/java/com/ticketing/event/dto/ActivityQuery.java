package com.ticketing.event.dto;

import com.ticketing.event.model.ActivityCategory;
import com.ticketing.event.model.ActivityStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record ActivityQuery(
        @Size(max = 80, message = "关键词不能超过80字") String keyword,
        ActivityCategory category,
        @Size(max = 64, message = "城市不能超过64字") String city,
        ActivityStatus status,
        @Min(value = 1, message = "页码至少为1") @Max(value = 10000, message = "页码不能超过10000") Integer page,
        @Min(value = 1, message = "每页至少1条") @Max(value = 50, message = "每页最多50条") Integer size) {
    public ActivityQuery {
        page = page == null ? 1 : page;
        size = size == null ? 12 : size;
    }
}
