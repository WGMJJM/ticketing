package com.ticketing.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

/** 仅受控本地开发使用，不连接业务用户表，不代替后续账号认证模块。 */
@Configuration
@ConditionalOnProperty(name = "ticketing.dev-admin.enabled", havingValue = "true")
public class DevAdminConfig {
    @Bean
    UserDetailsService devAdminUsers(@Value("${ticketing.dev-admin.username:}") String username,
                                    @Value("${ticketing.dev-admin.password:}") String password) {
        if (username.isBlank() || username.contains(":") || password.length() < 16) {
            throw new IllegalStateException("开发管理员启用时必须配置用户名及至少16位密码");
        }
        var user = User.withUsername(username)
                .password("{bcrypt}" + new BCryptPasswordEncoder().encode(password)).roles("ADMIN").build();
        return new InMemoryUserDetailsManager(user);
    }
}
