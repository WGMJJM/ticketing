package com.ticketing.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;

public record PasswordChangeRequest(
        @NotBlank(message = "请填写当前密码")
        @Size(max = 72, message = "密码长度不能超过72个字符") String currentPassword,
        @NotBlank(message = "请填写新密码")
        @Size(min = 8, max = 72, message = "新密码长度须为8至72个字符")
        @Pattern(regexp = "[^\\p{Cc}]*", message = "密码不能包含控制字符") String newPassword) {
    @AssertTrue(message = "密码的UTF-8编码不能超过72字节")
    public boolean isPasswordWithinByteLimit() {
        return withinByteLimit(currentPassword) && withinByteLimit(newPassword);
    }

    static boolean withinByteLimit(String value) {
        return value == null || value.getBytes(StandardCharsets.UTF_8).length <= 72;
    }

    @Override
    public String toString() { return "PasswordChangeRequest[redacted]"; }
}
