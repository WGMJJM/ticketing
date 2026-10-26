package com.ticketing.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public record RegisterRequest(
        @NotBlank(message = "请填写用户名")
        @Pattern(regexp = "[A-Za-z][A-Za-z0-9_]{2,31}", message = "用户名须为3至32位字母、数字或下划线，并以字母开头") String username,
        @NotBlank(message = "请填写邮箱") @Email(message = "请填写有效邮箱")
        @Size(max = 254, message = "邮箱不能超过254个字符")
        @Pattern(regexp = "[\\x21-\\x7E]+", message = "邮箱须使用英文字母、数字和英文符号") String email,
        @NotBlank(message = "请填写密码")
        @Size(min = 8, max = 72, message = "密码长度须为8至72个字符")
        @Pattern(regexp = "[^\\p{Cc}]*", message = "密码不能包含控制字符") String password,
        @NotBlank(message = "请填写邮箱验证码")
        @Pattern(regexp = "[0-9]{6}", message = "邮箱验证码须为6位数字") String code) {
    public RegisterRequest {
        username = username == null ? null : username.toLowerCase(Locale.ROOT);
        email = email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
    @AssertTrue(message = "密码的UTF-8编码不能超过72字节")
    public boolean isPasswordWithinByteLimit() {
        return password == null || password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }

    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("注册请求包含不允许指定的字段");
    }

    @Override
    public String toString() { return "RegisterRequest[redacted]"; }
}
