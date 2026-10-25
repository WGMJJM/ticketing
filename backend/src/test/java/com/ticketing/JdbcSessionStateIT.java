package com.ticketing;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 单连接真实复用，核对驱动缓存与MySQL实际状态，不以JDBC本地返回值替代数据库事实。 */
@SpringBootTest
class JdbcSessionStateIT {
    @Autowired HikariDataSource source;

    @Test void localSessionStatePreservesIsolationReadOnlyEnforcementAndPoolReset() {
        assertThat(source.getDataSourceProperties().getProperty("useLocalSessionState")).isEqualTo("true");
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(source.getJdbcUrl());
        config.setUsername(source.getUsername());
        config.setPassword(source.getPassword());
        Properties properties = new Properties();
        properties.putAll(source.getDataSourceProperties());
        config.setDataSourceProperties(properties);
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(1);
        try (HikariDataSource pool = new HikariDataSource(config)) {
            JdbcTemplate jdbc = new JdbcTemplate(pool);
            DataSourceTransactionManager manager = new DataSourceTransactionManager(pool);
            long connectionId = jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
            String initialIsolation = jdbc.queryForObject("SELECT @@session.transaction_isolation", String.class);
            for (int attempt = 0; attempt < 3; attempt++) {
                TransactionTemplate read = new TransactionTemplate(manager);
                read.setReadOnly(true);
                read.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
                read.executeWithoutResult(status -> {
                    assertThat(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class)).isEqualTo(connectionId);
                    assertThat(jdbc.queryForObject("SELECT @@session.transaction_isolation", String.class)).isEqualTo("READ-COMMITTED");
                    assertThat(jdbc.queryForObject("SELECT @@session.transaction_read_only", Integer.class)).isEqualTo(1);
                    assertThatThrownBy(() -> jdbc.update("UPDATE purchase_capacity SET pending_count=pending_count WHERE id=1"))
                            .isInstanceOf(DataAccessException.class);
                });
                assertThat(jdbc.queryForObject("SELECT @@session.transaction_isolation", String.class)).isEqualTo(initialIsolation);
                assertThat(jdbc.queryForObject("SELECT @@session.transaction_read_only", Integer.class)).isZero();
                TransactionTemplate write = new TransactionTemplate(manager);
                write.executeWithoutResult(status -> {
                    assertThat(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class)).isEqualTo(connectionId);
                    jdbc.update("UPDATE purchase_capacity SET pending_count=pending_count WHERE id=1");
                });
            }
        }
    }
}
