package com.ticketing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AftercareResolutionRequest(
        @NotBlank(message = "请输入人工处理结果")
        @Size(max = 255, message = "人工处理结果不能超过255字") String note) {
}
