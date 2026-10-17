package com.ticketing.auth;

import com.ticketing.auth.dto.RegisterRequest;
import com.ticketing.auth.service.AccountService;
import jakarta.validation.Validator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "ticketing.admin-bootstrap.enabled", havingValue = "true")
public class AdminBootstrap implements ApplicationRunner {
    private final AccountService accounts;
    private final Validator validator;
    private RegisterRequest credentials;

    public AdminBootstrap(AccountService accounts, Validator validator,
            @Value("${ticketing.admin-bootstrap.username:}") String username,
            @Value("${ticketing.admin-bootstrap.password:}") String password) {
        this.accounts = accounts;
        this.validator = validator;
        this.credentials = new RegisterRequest(username, password);
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            if (!validator.validate(credentials).isEmpty() || credentials.password().length() < 16) {
                throw new IllegalStateException("管理员初始化需配置合法用户名及至少16个字符且不超过72字节的密码");
            }
            accounts.initializeAdmin(credentials);
        } finally {
            credentials = null;
        }
    }
}
