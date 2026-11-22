package com.ticketing.controller;

import com.ticketing.common.ApiResponse;
import com.ticketing.dto.RegisterRequest;
import com.ticketing.dto.EmailCodeRequest;
import com.ticketing.dto.PasswordChangeRequest;
import com.ticketing.dto.PasswordResetRequest;
import com.ticketing.exception.BusinessException;
import com.ticketing.security.AccountSessions;
import com.ticketing.security.ClientAddressResolver;
import com.ticketing.security.AccountPrincipal;
import com.ticketing.service.AccountService;
import com.ticketing.service.RegistrationEmailService;
import com.ticketing.vo.UserView;
import com.ticketing.vo.AccountSessionView;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.CookieClearingLogoutHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AccountService accounts;
    private final RegistrationEmailService emails;
    private final ClientAddressResolver addresses;
    private final AccountSessions sessions;
    private final SecurityContextLogoutHandler logout = new SecurityContextLogoutHandler();
    private final CookieClearingLogoutHandler cookies = new CookieClearingLogoutHandler("JSESSIONID");

    public AuthController(AccountService accounts, RegistrationEmailService emails, ClientAddressResolver addresses,
            AccountSessions sessions) {
        this.accounts = accounts; this.emails = emails; this.addresses = addresses; this.sessions = sessions;
    }

    @GetMapping("/csrf")
    public ApiResponse<CsrfView> csrf(CsrfToken token) {
        return ApiResponse.success(new CsrfView(token.getHeaderName(), token.getToken()));
    }

    @PostMapping("/register")
    public ApiResponse<UserView> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.success(accounts.register(request));
    }

    @GetMapping("/me")
    public ApiResponse<UserView> me(@AuthenticationPrincipal AccountPrincipal principal) {
        return ApiResponse.success(UserView.from(principal));
    }

    @PostMapping("/email-code")
    public ApiResponse<Void> emailCode(@Valid @RequestBody EmailCodeRequest request, HttpServletRequest http) {
        emails.send(request.email(), clientAddress(http));
        return ApiResponse.success(null);
    }

    @PostMapping("/password/reset-code")
    public ResponseEntity<ApiResponse<Void>> passwordResetCode(
            @Valid @RequestBody EmailCodeRequest request, HttpServletRequest http) {
        accounts.requestPasswordReset(request.email(), clientAddress(http));
        return privateResponse(null);
    }

    @PostMapping("/password/reset")
    public ResponseEntity<ApiResponse<Void>> resetPassword(@Valid @RequestBody PasswordResetRequest request,
            @AuthenticationPrincipal AccountPrincipal principal, HttpServletRequest http, HttpServletResponse response) {
        long userId = accounts.resetPassword(request);
        if (principal != null && principal.getId() == userId) endCurrentSession(http, response);
        return privateResponse(null);
    }

    @PostMapping("/password/change")
    public ResponseEntity<ApiResponse<Void>> changePassword(@AuthenticationPrincipal AccountPrincipal principal,
            @Valid @RequestBody PasswordChangeRequest request, HttpServletRequest http, HttpServletResponse response) {
        accounts.changePassword(principal, request);
        endCurrentSession(http, response);
        return privateResponse(null);
    }

    @GetMapping("/sessions")
    public ResponseEntity<ApiResponse<List<AccountSessionView>>> sessions(
            @AuthenticationPrincipal AccountPrincipal principal, HttpServletRequest http) {
        return privateResponse(sessions.list(principal, currentSessionId(http)));
    }

    @DeleteMapping("/sessions/others")
    public ResponseEntity<ApiResponse<Void>> revokeOthers(
            @AuthenticationPrincipal AccountPrincipal principal, HttpServletRequest http) {
        sessions.revokeOthers(principal, currentSessionId(http));
        return privateResponse(null);
    }

    @DeleteMapping("/sessions/{id}")
    public ResponseEntity<ApiResponse<Void>> revokeSession(@AuthenticationPrincipal AccountPrincipal principal,
            @PathVariable @Pattern(regexp = "[0-9a-f]{64}") String id, HttpServletRequest http) {
        sessions.revoke(principal, currentSessionId(http), id);
        return privateResponse(null);
    }

    private String clientAddress(HttpServletRequest request) {
        try { return addresses.resolve(request); }
        catch (IllegalArgumentException exception) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_CLIENT_ADDRESS", "安全来源信息无效，请稍后重试");
        }
    }

    private String currentSessionId(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session == null) throw new BusinessException(HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED", "请重新登录");
        return session.getId();
    }

    private void endCurrentSession(HttpServletRequest request, HttpServletResponse response) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        logout.logout(request, response, authentication);
        cookies.logout(request, response, authentication);
    }

    private <T> ResponseEntity<ApiResponse<T>> privateResponse(T data) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(data));
    }

    public record CsrfView(String headerName, String token) {
    }
}
