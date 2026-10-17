package com.ticketing.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ticketing.auth.entity.UserAccount;
import com.ticketing.auth.mapper.UserAccountMapper;
import com.ticketing.auth.security.AccountPrincipal;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class AccountUserDetailsService implements UserDetailsService {
    private final UserAccountMapper accounts;
    public AccountUserDetailsService(UserAccountMapper accounts) { this.accounts = accounts; }

    @Override
    public AccountPrincipal loadUserByUsername(String username) {
        UserAccount account = accounts.selectOne(new LambdaQueryWrapper<UserAccount>().eq(UserAccount::getUsername, username));
        if (account == null) throw new UsernameNotFoundException("用户名或密码不正确");
        return new AccountPrincipal(account);
    }
}
