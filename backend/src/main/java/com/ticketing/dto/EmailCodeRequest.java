package com.ticketing.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Locale;

public record EmailCodeRequest(
        @NotBlank(message = "请填写邮箱") @Email(message = "请填写有效邮箱")
        @Size(max = 254, message = "邮箱不能超过254个字符")
        @Pattern(regexp = "[\\x21-\\x7E]+", message = "邮箱须使用英文字母、数字和英文符号") String email) {
    public EmailCodeRequest {
        email = email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("验证码请求包含不允许指定的字段");
    }

    @Override
    public String toString() { return "EmailCodeRequest[redacted]"; }
}
