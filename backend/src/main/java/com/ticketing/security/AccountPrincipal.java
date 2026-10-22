package com.ticketing.security;

import com.ticketing.entity.UserAccount;
import java.io.Serial;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.User;

/** 会话身份包含可信数据库ID；User的凭据擦除机制在认证后清除密码哈希。 */
public class AccountPrincipal extends User {
    @Serial
    private static final long serialVersionUID = 1L;
    private final Long id;
    private final String role;

    public AccountPrincipal(UserAccount account) {
        this(account, account.getPasswordHash());
    }

    private AccountPrincipal(UserAccount account, String password) {
        super(account.getUsername(), password, "ACTIVE".equals(account.getStatus()),
                true, true, true, AuthorityUtils.createAuthorityList("ROLE_" + account.getRole()));
        this.id = account.getId();
        this.role = account.getRole();
    }

    public static AccountPrincipal forSession(UserAccount account) {
        var principal = new AccountPrincipal(account, "");
        principal.eraseCredentials();
        return principal;
    }

    public Long getId() { return id; }
    public String getRole() { return role; }
}
