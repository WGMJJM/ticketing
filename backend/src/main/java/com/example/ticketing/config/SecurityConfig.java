package com.example.ticketing.config;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import com.example.ticketing.common.api.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper objectMapper,
            @Value("${ticketing.dev-admin.enabled:false}") boolean devAdminEnabled) throws Exception {
        http
                .authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/system/status", "/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/activities", "/api/v1/activities/*", "/api/v1/activities/*/sessions").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .requestMatchers("/error").permitAll()
                        .anyRequest().denyAll())
                .csrf(Customizer.withDefaults())
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) -> {
                            int status = request.getRequestURI().startsWith("/api/v1/admin/") ? 401 : 403;
                            writeError(response, objectMapper, status, "AUTHENTICATION_REQUIRED", "请先完成管理员认证");
                        })
                        .accessDeniedHandler((request, response, exception) ->
                                writeError(response, objectMapper, 403, "ACCESS_DENIED", "无权执行此操作或安全校验已过期，请重新认证")));
        if (devAdminEnabled) {
            http.httpBasic(basic -> basic.authenticationEntryPoint((request, response, exception) ->
                    writeError(response, objectMapper, 401, "INVALID_CREDENTIALS", "管理员账号或密码不正确")));
        } else {
            http.httpBasic(AbstractHttpConfigurer::disable);
        }
        return http.build();
    }

    private void writeError(HttpServletResponse response, ObjectMapper mapper, int status, String code, String message)
            throws java.io.IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        mapper.writeValue(response.getOutputStream(), ApiResponse.failure(code, message));
    }
}
