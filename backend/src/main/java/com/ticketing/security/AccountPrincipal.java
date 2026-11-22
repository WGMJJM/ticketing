package com.ticketing.security;

import com.ticketing.entity.UserAccount;

import java.io.Serial;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.User;

/** 会话身份包含可信数据库ID；User的凭据擦除机制在认证后清除密码哈希。 */
public class AccountPrincipal extends User {
    @Serial
    private static final long serialVersionUID = 1L;
    private final Long id;
    private final String role;
    private final String credentialStamp;

    public AccountPrincipal(UserAccount account) {
        this(account, account.getPasswordHash());
    }

    private AccountPrincipal(UserAccount account, String password) {
        super(account.getUsername(), password, "ACTIVE".equals(account.getStatus()),
                true, true, true, AuthorityUtils.createAuthorityList("ROLE_" + account.getRole()));
        this.id = account.getId();
        this.role = account.getRole();
        this.credentialStamp = credentialStamp(account.getPasswordHash());
    }

    public static AccountPrincipal forSession(UserAccount account) {
        var principal = new AccountPrincipal(account, "");
        principal.eraseCredentials();
        return principal;
    }

    public Long getId() { return id; }
    public String getRole() { return role; }
    public String credentialStamp() { return credentialStamp; }

    /** BCrypt 每次写入使用随机盐，会话只保留单向的凭据版本指纹。 */
    public static String credentialStamp(String passwordHash) {
        return digest("password\0" + passwordHash);
    }

    static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }
}
