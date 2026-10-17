package com.ticketing.config;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import com.ticketing.common.api.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.auth.dto.UserView;
import com.ticketing.auth.security.AccountPrincipal;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.savedrequest.NullRequestCache;

@Configuration
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
        http
                .authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/system/status", "/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/activities", "/api/v1/activities/*", "/api/v1/activities/*/sessions").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/activities/*/sessions/*/tickets").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/logout").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/me").authenticated()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .requestMatchers("/error").permitAll()
                        .anyRequest().denyAll())
                .csrf(Customizer.withDefaults())
                .httpBasic(AbstractHttpConfigurer::disable)
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .sessionManagement(session -> session.sessionFixation(fixation -> fixation.changeSessionId()))
                .formLogin(form -> form.loginPage("/api/v1/auth/login").loginProcessingUrl("/api/v1/auth/login")
                        .successHandler((request, response, authentication) -> writeResponse(response, objectMapper, 200,
                                ApiResponse.success(UserView.from((AccountPrincipal) authentication.getPrincipal()))))
                        .failureHandler((request, response, exception) -> writeError(response, objectMapper, 401,
                                "INVALID_CREDENTIALS", exception instanceof DisabledException ? "账号已被禁用，请联系管理员" : "用户名或密码不正确")))
                .logout(logout -> logout.logoutUrl("/api/v1/auth/logout").invalidateHttpSession(true)
                        .clearAuthentication(true).deleteCookies("JSESSIONID")
                        .logoutSuccessHandler((request, response, authentication) ->
                                writeResponse(response, objectMapper, 200, ApiResponse.success(null))))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) -> {
                            String path = request.getRequestURI();
                            int status = path.startsWith("/api/v1/admin/") || path.equals("/api/v1/auth/me") ? 401 : 403;
                            writeError(response, objectMapper, status, "AUTHENTICATION_REQUIRED", "请先登录后再操作");
                        })
                        .accessDeniedHandler((request, response, exception) ->
                                writeError(response, objectMapper, 403, "ACCESS_DENIED", "无权执行此操作或安全校验已过期，请刷新后重试")));
        return http.build();
    }

    private void writeError(HttpServletResponse response, ObjectMapper mapper, int status, String code, String message)
            throws java.io.IOException {
        writeResponse(response, mapper, status, ApiResponse.failure(code, message));
    }

    private void writeResponse(HttpServletResponse response, ObjectMapper mapper, int status, Object body)
            throws java.io.IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        mapper.writeValue(response.getOutputStream(), body);
    }
}
