package com.ticketing.auth.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.auth.entity.UserAccount;
import com.ticketing.auth.mapper.UserAccountMapper;
import com.ticketing.common.api.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.CookieClearingLogoutHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/** 只在受保护端点校验数据库身份，避免公开浏览额外查询账号。 */
public class CurrentAccountFilter extends OncePerRequestFilter {
    private final UserAccountMapper accounts;
    private final RequestMatcher protectedRequests;
    private final SecurityContextRepository contexts;
    private final ObjectMapper json;
    private final SecurityContextLogoutHandler logout = new SecurityContextLogoutHandler();
    private final CookieClearingLogoutHandler cookies = new CookieClearingLogoutHandler("JSESSIONID");

    public CurrentAccountFilter(UserAccountMapper accounts, RequestMatcher protectedRequests,
            SecurityContextRepository contexts, ObjectMapper json) {
        this.accounts = accounts;
        this.protectedRequests = protectedRequests;
        this.contexts = contexts;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !protectedRequests.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication instanceof AnonymousAuthenticationToken) {
            chain.doFilter(request, response);
            return;
        }
        UserAccount account = null;
        if (authentication.isAuthenticated() && authentication.getPrincipal() instanceof AccountPrincipal principal) {
            account = accounts.selectOne(new LambdaQueryWrapper<UserAccount>()
                    .select(UserAccount::getId, UserAccount::getUsername, UserAccount::getStatus, UserAccount::getRole)
                    .eq(UserAccount::getId, principal.getId()));
        }
        if (account == null || !"ACTIVE".equals(account.getStatus())) {
            logout.logout(request, response, authentication);
            cookies.logout(request, response, authentication);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.setHeader("Cache-Control", "no-store");
            json.writeValue(response.getOutputStream(), ApiResponse.failure("ACCOUNT_UNAVAILABLE", "账号已停用或不存在，请重新登录"));
            return;
        }
        AccountPrincipal previous = (AccountPrincipal) authentication.getPrincipal();
        if (!previous.getRole().equals(account.getRole()) || !previous.getUsername().equals(account.getUsername())) {
            AccountPrincipal principal = AccountPrincipal.forSession(account);
            var refreshed = UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
            refreshed.setDetails(authentication.getDetails());
            // 创建新Context，避免原地修改并发请求共享的会话Context；显式保存供后续请求恢复。
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(refreshed);
            SecurityContextHolder.setContext(context);
            contexts.saveContext(context, request, response);
        }
        chain.doFilter(request, response);
    }
}
