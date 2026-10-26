package com.ticketing.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.util.Base64;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("ticketing.registration")
public record RegistrationProperties(
        @DefaultValue("false") boolean emailEnabled,
        String codeSecret,
        @Email String mailFrom,
        @DefaultValue("default") @Pattern(regexp = "[A-Za-z0-9_-]{1,32}") String namespace,
        @DefaultValue("true") boolean bloomEnabled,
        @DefaultValue("1000000") @Min(1000) @Max(10000000) int bloomExpectedAccounts,
        @DefaultValue("0.01") @DecimalMin("0.0001") @DecimalMax("0.1") double bloomFalsePositiveRate) {

    @AssertTrue(message = "启用注册邮件时须配置发件邮箱及32至128字节Base64验证码密钥")
    public boolean isEmailConfigurationValid() {
        if (!emailEnabled) return true;
        try {
            int length = Base64.getDecoder().decode(codeSecret == null ? "" : codeSecret).length;
            return length >= 32 && length <= 128 && mailFrom != null && !mailFrom.isBlank();
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    @Override
    public String toString() {
        return "RegistrationProperties[emailEnabled=" + emailEnabled + ", bloomEnabled=" + bloomEnabled + "]";
    }
}
