package com.ticketing.staff;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record StaffQuery(
        @Size(max = 64, message = "用户名不能超过64位") String username,
        @Min(value = 1, message = "页码至少为1") @Max(value = 10000, message = "页码超出范围") Integer page,
        @Min(value = 1, message = "每页至少1条") @Max(value = 50, message = "每页最多50条") Integer size) {
    public int pageNumber() {
        return page == null ? 1 : page;
    }

    public int pageSize() {
        return size == null ? 10 : size;
    }
}
