package com.ticketing.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ticketing.dto.RegisterRequest;
import com.ticketing.dto.UserView;
import com.ticketing.entity.UserAccount;
import com.ticketing.mapper.UserAccountMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ticketing.common.BusinessException;

@Service
public class AccountService {
    private final UserAccountMapper accounts;
    private final PasswordEncoder passwords;

    public AccountService(UserAccountMapper accounts, PasswordEncoder passwords) {
        this.accounts = accounts;
        this.passwords = passwords;
    }

    @Transactional
    public UserView register(RegisterRequest request) {
        return create(request, "USER");
    }

    /** 仅由显式开启的启动初始化调用，不提供提权或重置密码的公开接口。 */
    @Transactional
    public void initializeAdmin(RegisterRequest request) {
        UserAccount existing = find(request.username());
        if (existing != null) {
            if (!"ADMIN".equals(existing.getRole()) || !"ACTIVE".equals(existing.getStatus())) {
                throw new IllegalStateException("初始化管理员账号已被其他角色或禁用账号占用，禁止自动提权");
            }
            return;
        }
        create(request, "ADMIN");
    }

    private UserView create(RegisterRequest request, String role) {
        if (find(request.username()) != null) throw duplicate();
        UserAccount account = new UserAccount();
        account.setUsername(request.username());
        account.setPasswordHash(passwords.encode(request.password()));
        account.setStatus("ACTIVE");
        account.setRole(role);
        account.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        account.setUpdatedAt(account.getCreatedAt());
        try { accounts.insert(account); } catch (DuplicateKeyException exception) { throw duplicate(); }
        return UserView.from(account);
    }

    private UserAccount find(String username) {
        return accounts.selectOne(new LambdaQueryWrapper<UserAccount>().eq(UserAccount::getUsername, username));
    }

    private BusinessException duplicate() {
        return new BusinessException(HttpStatus.CONFLICT, "USERNAME_EXISTS", "用户名已被使用，请更换用户名");
    }
}
