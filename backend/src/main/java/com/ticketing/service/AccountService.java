package com.ticketing.service;

import com.ticketing.dto.RegisterRequest;
import com.ticketing.dto.AdminCredentials;
import com.ticketing.entity.UserAccount;
import com.ticketing.exception.BusinessException;
import com.ticketing.mapper.UserAccountMapper;
import com.ticketing.vo.UserView;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.validation.Validator;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class AccountService {
    private final UserAccountMapper accounts;
    private final PasswordEncoder passwords;
    private final RegistrationBloomFilter bloom;
    private final RegistrationEmailService emails;
    private final Validator validator;
    private final TransactionTemplate write;

    public AccountService(UserAccountMapper accounts, PasswordEncoder passwords, RegistrationBloomFilter bloom,
            RegistrationEmailService emails, Validator validator, PlatformTransactionManager manager) {
        this.accounts = accounts;
        this.passwords = passwords;
        this.bloom = bloom;
        this.emails = emails;
        this.validator = validator;
        write = new TransactionTemplate(manager);
        write.setTimeout(10);
    }

    /** 验证码、BCrypt及Redis均在写事务外；MySQL唯一索引最终裁决并发冲突。 */
    public UserView register(RegisterRequest request) {
        requireOutsideTransaction();
        if (!validator.validate(request).isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "请完整填写合法的用户名、邮箱、密码及验证码");
        }
        if (bloom.usernameMayExist(request.username()) && find(request.username()) != null) {
            throw new BusinessException(HttpStatus.CONFLICT, "USERNAME_EXISTS", "用户名已被使用，请更换用户名");
        }
        if (bloom.emailMayExist(request.email()) && accounts.exists(new LambdaQueryWrapper<UserAccount>()
                .eq(UserAccount::getEmail, request.email()))) {
            throw new BusinessException(HttpStatus.CONFLICT, "EMAIL_EXISTS", "邮箱已被注册，请更换邮箱");
        }
        emails.consume(request.email(), request.code());
        String passwordHash = passwords.encode(request.password());
        UserAccount account = insert(request.username(), request.email(), passwordHash, "USER");
        bloom.committed(account.getUsername(), account.getEmail());
        return UserView.from(account);
    }

    /** 仅由显式开启的启动初始化调用，不提供提权或重置密码的公开接口。 */
    public void initializeAdmin(AdminCredentials request) {
        requireOutsideTransaction();
        if (!validator.validate(request).isEmpty()) throw new IllegalArgumentException("管理员初始化凭据不符合要求");
        UserAccount existing = find(request.username());
        if (existing != null) {
            if (!"ADMIN".equals(existing.getRole()) || !"ACTIVE".equals(existing.getStatus())) {
                throw new IllegalStateException("初始化管理员账号已被其他角色或禁用账号占用，禁止自动提权");
            }
            return;
        }
        UserAccount account = insert(request.username(), null, passwords.encode(request.password()), "ADMIN");
        bloom.committed(account.getUsername(), null);
    }

    private UserAccount insert(String username, String email, String passwordHash, String role) {
        try {
            return write.execute(status -> {
                UserAccount account = new UserAccount();
                account.setUsername(username);
                account.setEmail(email);
                account.setPasswordHash(passwordHash);
                account.setStatus("ACTIVE");
                account.setRole(role);
                account.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
                account.setUpdatedAt(account.getCreatedAt());
                if (email != null) account.setEmailVerifiedAt(account.getCreatedAt());
                accounts.insert(account);
                return account;
            });
        } catch (DuplicateKeyException exception) {
            // 不解析驱动异常字符串；事务已回滚，验证码不补偿，用户需重新获取。
            throw new BusinessException(HttpStatus.CONFLICT, "ACCOUNT_EXISTS", "用户名或邮箱已被注册，请更换后重新获取验证码");
        }
    }

    private UserAccount find(String username) {
        return accounts.selectOne(new LambdaQueryWrapper<UserAccount>().eq(UserAccount::getUsername, username));
    }

    private void requireOutsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("注册及管理员初始化必须从数据库事务外调用");
        }
    }
}
