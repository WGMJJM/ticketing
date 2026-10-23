package com.ticketing.checkin;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CheckinRequest(@NotBlank(message = "请扫描或输入完整电子票凭证")
        @Size(max = 256, message = "电子票凭证长度不正确") String credential) {
    @JsonAnySetter
    public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("核销请求含不允许的字段"); }
    @Override
    public String toString() { return "CheckinRequest[credential=已隐藏]"; }
}
