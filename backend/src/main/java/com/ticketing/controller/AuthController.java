package com.ticketing.controller;

import com.ticketing.common.ApiResponse;
import com.ticketing.dto.RegisterRequest;
import com.ticketing.dto.EmailCodeRequest;
import com.ticketing.exception.BusinessException;
import com.ticketing.security.ClientAddressResolver;
import com.ticketing.security.AccountPrincipal;
import com.ticketing.service.AccountService;
import com.ticketing.service.RegistrationEmailService;
import com.ticketing.vo.UserView;

import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
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
    public AuthController(AccountService accounts, RegistrationEmailService emails, ClientAddressResolver addresses) {
        this.accounts = accounts; this.emails = emails; this.addresses = addresses;
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
        String address;
        try { address = addresses.resolve(http); }
        catch (IllegalArgumentException exception) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_CLIENT_ADDRESS", "安全来源信息无效，请稍后重试");
        }
        emails.send(request.email(), address);
        return ApiResponse.success(null);
    }

    public record CsrfView(String headerName, String token) {
    }
}
