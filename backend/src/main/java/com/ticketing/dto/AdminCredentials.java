package com.ticketing.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** 显式初始化管理员的内部凭据，不作为公开注册请求使用。 */
public record AdminCredentials(
        @NotBlank @Pattern(regexp = "[A-Za-z][A-Za-z0-9_]{2,31}") String username,
        @NotBlank @Size(min = 16, max = 72) @Pattern(regexp = "[^\\p{Cc}]*") String password) {
    public AdminCredentials {
        username = username == null ? null : username.toLowerCase(Locale.ROOT);
    }

    @AssertTrue
    public boolean isPasswordWithinByteLimit() {
        return password == null || password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }

    @Override
    public String toString() { return "AdminCredentials[redacted]"; }
}
