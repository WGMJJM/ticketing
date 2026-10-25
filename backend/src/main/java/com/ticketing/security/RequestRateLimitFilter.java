package com.ticketing.security;

import com.ticketing.common.ApiResponse;
import com.ticketing.security.RedisRateLimiter.Decision;
import com.ticketing.security.RedisRateLimiter.Outcome;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/** 三个实例分别保护会话创建、认证和授权后的业务提交，仅由Security链注册。 */
public class RequestRateLimitFilter extends OncePerRequestFilter {
    private static final PathPatternRequestMatcher.Builder PATHS = PathPatternRequestMatcher.withDefaults();
    private static final RequestMatcher LOGIN = PATHS.matcher(HttpMethod.POST, "/api/v1/auth/login");
    private static final RequestMatcher REGISTER = PATHS.matcher(HttpMethod.POST, "/api/v1/auth/register");
    private static final RequestMatcher ORDER = PATHS.matcher(HttpMethod.POST, "/api/v1/orders");
    private static final RequestMatcher WAITLIST = PATHS.matcher(HttpMethod.POST, "/api/v1/waitlist");
    private static final RequestMatcher CSRF = PATHS.matcher("/api/v1/auth/csrf");
    private final RedisRateLimiter limiter;
    private final ObjectMapper json;
    private final Scope scope;
    private final ClientAddressResolver addresses;

    public RequestRateLimitFilter(RedisRateLimiter limiter, ObjectMapper json, Scope scope, ClientAddressResolver addresses) {
        this.limiter = limiter;
        this.json = json;
        this.scope = scope;
        this.addresses = addresses;
    }

    @Override
    protected String getAlreadyFilteredAttributeName() {
        return getClass().getName() + "." + scope.name() + ALREADY_FILTERED_SUFFIX;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (scope == Scope.SESSION_CREATION) {
            // 不能只检查Cookie是否存在；无效/过期Cookie仍可能触发新Session。
            boolean csrfRead = CSRF.matches(request) && HttpMethod.GET.matches(request.getMethod());
            return request.getSession(false) != null || !(csrfRead || CsrfFilter.DEFAULT_CSRF_MATCHER.matches(request));
        }
        return scope == Scope.AUTHENTICATION
                ? !LOGIN.matches(request) && !REGISTER.matches(request)
                : !ORDER.matches(request) && !WAITLIST.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Decision decision;
        String address = null;
        if (scope != Scope.AUTHORIZED_BUSINESS) {
            try { address = addresses.resolve(request); }
            catch (IllegalArgumentException exception) {
                response.setStatus(HttpStatus.BAD_REQUEST.value());
                response.setContentType("application/json;charset=UTF-8");
                response.setHeader("Cache-Control", "no-store");
                json.writeValue(response.getOutputStream(), ApiResponse.failure("INVALID_CLIENT_ADDRESS", "安全来源信息无效，请稍后重试"));
                return;
            }
        }
        if (scope == Scope.SESSION_CREATION) {
            decision = limiter.csrfSession(address);
        } else if (scope == Scope.AUTHENTICATION) {
            // 只读formLogin本身要使用的表单参数；注册分支不读取JSON请求体。
            decision = LOGIN.matches(request) ? limiter.login(address, request.getParameter("username"))
                    : limiter.registration(address);
        } else {
            AccountPrincipal principal = (AccountPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
            decision = ORDER.matches(request) ? limiter.order(principal.getId()) : limiter.waitlist(principal.getId());
        }
        if (decision.outcome() == Outcome.ALLOWED) {
            // 不捕获下游认证或业务异常，保留既有401/403/409语义。
            chain.doFilter(request, response);
            return;
        }
        boolean limited = decision.outcome() == Outcome.LIMITED;
        response.setStatus(limited ? HttpStatus.TOO_MANY_REQUESTS.value() : HttpStatus.SERVICE_UNAVAILABLE.value());
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Retry-After", Long.toString(decision.retryAfterSeconds()));
        json.writeValue(response.getOutputStream(), ApiResponse.failure(
                limited ? "TOO_MANY_REQUESTS" : "RATE_LIMIT_UNAVAILABLE",
                limited ? "操作过于频繁，请在" + decision.retryAfterSeconds() + "秒后重试" : "安全校验服务暂不可用，请稍后重试"));
    }

    public enum Scope { SESSION_CREATION, AUTHENTICATION, AUTHORIZED_BUSINESS }
}
