package com.ticketing.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Locale;

public record PasswordResetRequest(
        @NotBlank(message = "请填写邮箱") @Email(message = "请填写有效邮箱")
        @Size(max = 254, message = "邮箱不能超过254个字符")
        @Pattern(regexp = "[\\x21-\\x7E]+", message = "邮箱须使用英文字符") String email,
        @NotBlank(message = "请填写邮箱验证码")
        @Pattern(regexp = "[0-9]{6}", message = "邮箱验证码须为6位数字") String code,
        @NotBlank(message = "请填写新密码")
        @Size(min = 8, max = 72, message = "新密码长度须为8至72个字符")
        @Pattern(regexp = "[^\\p{Cc}]*", message = "密码不能包含控制字符") String newPassword) {
    public PasswordResetRequest {
        email = email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    @AssertTrue(message = "密码的UTF-8编码不能超过72字节")
    public boolean isPasswordWithinByteLimit() { return PasswordChangeRequest.withinByteLimit(newPassword); }

    @Override
    public String toString() { return "PasswordResetRequest[redacted]"; }
}
