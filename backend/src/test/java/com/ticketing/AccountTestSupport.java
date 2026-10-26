package com.ticketing;

import com.ticketing.vo.UserView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** 非注册专项测试使用的历史无邮箱账号夹具，不提供任何生产注册旁路。 */
public final class AccountTestSupport {
    private AccountTestSupport() { }

    public static UserView legacyUser(JdbcTemplate db, String username, String password) {
        return insert(db, username, password, "USER");
    }

    public static UserView legacyAdmin(JdbcTemplate db, String username, String password) {
        return insert(db, username, password, "ADMIN");
    }

    private static UserView insert(JdbcTemplate db, String username, String password, String role) {
        db.update("INSERT INTO user_account(username,password_hash,status,role) VALUES(?,?,'ACTIVE',?)",
                username, new BCryptPasswordEncoder().encode(password), role);
        Long id = db.queryForObject("SELECT id FROM user_account WHERE username=?", Long.class, username);
        return new UserView(id.toString(), username, role);
    }
}
