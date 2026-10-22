package com.ticketing.config;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import com.ticketing.common.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.dto.UserView;
import com.ticketing.security.AccountPrincipal;
import com.ticketing.security.CurrentAccountFilter;
import com.ticketing.mapper.UserAccountMapper;
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
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;

@Configuration
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper objectMapper, UserAccountMapper accounts) throws Exception {
        var paths = PathPatternRequestMatcher.withDefaults();
        var currentUser = paths.matcher(HttpMethod.GET, "/api/v1/auth/me");
        var administration = paths.matcher("/api/v1/admin/**");
        var orderRequests = paths.matcher("/api/v1/orders/**");
        var waitlistRequests = paths.matcher("/api/v1/waitlist/**");
        var ticketRequests = paths.matcher("/api/v1/tickets/**");
        var contexts = new DelegatingSecurityContextRepository(
                new RequestAttributeSecurityContextRepository(), new HttpSessionSecurityContextRepository());
        http
                .authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/system/status", "/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/activities", "/api/v1/activities/*", "/api/v1/activities/*/sessions").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/activities/*/sessions/*/tickets").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/logout").permitAll()
                        .requestMatchers(currentUser).authenticated()
                        .requestMatchers(orderRequests).authenticated()
                        .requestMatchers(waitlistRequests).authenticated()
                        .requestMatchers(ticketRequests).authenticated()
                        .requestMatchers(administration).hasRole("ADMIN")
                        .requestMatchers("/error").permitAll()
                        .anyRequest().denyAll())
                .csrf(Customizer.withDefaults())
                .securityContext(context -> context.securityContextRepository(contexts))
                .addFilterBefore(new CurrentAccountFilter(accounts, new OrRequestMatcher(currentUser, administration, orderRequests, waitlistRequests, ticketRequests), contexts, objectMapper),
                        AuthorizationFilter.class)
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
                            int status = path.startsWith("/api/v1/admin/") || path.equals("/api/v1/auth/me")
                                    || path.equals("/api/v1/orders") || path.startsWith("/api/v1/orders/")
                                    || path.equals("/api/v1/waitlist") || path.startsWith("/api/v1/waitlist/")
                                    || path.equals("/api/v1/tickets") || path.startsWith("/api/v1/tickets/") ? 401 : 403;
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
