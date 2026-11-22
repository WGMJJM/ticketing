package com.ticketing.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record TicketBatchCreateRequest(
        @NotEmpty(message = "请至少填写一个票档") List<@NotNull @Valid TicketWriteRequest> tickets) {

    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("批量票档请求包含不允许修改的字段");
    }
}
