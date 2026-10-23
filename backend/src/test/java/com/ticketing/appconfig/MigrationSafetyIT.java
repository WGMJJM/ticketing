package com.ticketing.appconfig;

import com.ticketing.appconfig.MigrationSafetyConfig;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 全局策略故障仅允许固定专用实例及匹配随机标记，绝不修改开发MySQL。 */
class MigrationSafetyIT {
    String schema,url;
    JdbcTemplate root,db;
    @BeforeEach void isolatedSchema() {
        assumeTrue("13307".equals(System.getenv("MYSQL_PORT")) && System.getenv("MYSQL_DATABASE").endsWith("_it")
                && System.getenv("RECOVERY_TEST_GUARD")!=null,"仅在显式专用恢复验证环境执行");
        root=jdbc("ticketing_perf",System.getenv("MYSQL_USER"),System.getenv("MYSQL_PASSWORD"));
        assertThat(root.queryForObject("SELECT token FROM performance_control.environment_guard",String.class)).isEqualTo(System.getenv("RECOVERY_TEST_GUARD"));
        schema="ticketing_v7_guard_"+UUID.randomUUID().toString().replace("-","");
        root.execute("CREATE DATABASE `"+schema+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        db=jdbc(schema,System.getenv("MYSQL_USER"),System.getenv("MYSQL_PASSWORD"));
        url="jdbc:mysql://127.0.0.1:13307/"+schema+"?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
    }
    JdbcTemplate jdbc(String database,String user,String password){return new JdbcTemplate(new DriverManagerDataSource("jdbc:mysql://127.0.0.1:13307/"+database+"?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",user,password));}
    void grant(boolean trigger){
        String privileges=trigger?"ALL PRIVILEGES":"SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,INDEX,REFERENCES,DROP";
        root.execute("GRANT "+privileges+" ON `"+schema+"`.* TO 'ticketing_perf'@'%'");
    }
    Flyway migration(String target){
        var builder=Flyway.configure().dataSource(url,"ticketing_perf",System.getenv("MIGRATION_TEST_PASSWORD"))
                .locations("classpath:db/migration").cleanDisabled(true);
        if(target!=null)builder.target(target);
        return builder.load();
    }
    void guarded(Flyway flyway){new MigrationSafetyConfig().migrationSafetyStrategy().migrate(flyway);}
    @Test void schemaScopedOrdinaryAccountMigratesFreshDatabaseAndRepeatedStartupSafely(){
        grant(true);var flyway=migration(null);guarded(flyway);guarded(flyway);flyway.validate();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1",Integer.class)).isEqualTo(8);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE()",Integer.class)).isEqualTo(2);
        var ordinary=jdbc(schema,"ticketing_perf",System.getenv("MIGRATION_TEST_PASSWORD"));
        assertThat(ordinary.queryForList("SHOW GRANTS",String.class)).noneMatch(value->value.contains("SUPER")||value.contains("ALL PRIVILEGES ON *.*"));
    }
    @Test void absentTriggerPrivilegeStopsBeforeAnyDdlAndGrantingOnlyThatPrivilegeEnablesUpgrade(){
        grant(false);var flyway=migration(null);
        assertThatThrownBy(()->guarded(flyway)).hasMessageContaining("STOP_TRIGGER_PRIVILEGE");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",Integer.class)).isZero();
        root.execute("GRANT TRIGGER ON `"+schema+"`.* TO 'ticketing_perf'@'%'");
        guarded(flyway);flyway.validate();
    }
    @Test void v6UpgradeAndMissingAppliedTriggerAreDistinguished(){
        grant(true);migration("6").migrate();var flyway=migration(null);guarded(flyway);flyway.validate();
        db.execute("DROP TRIGGER admin_audit_no_update");
        assertThatThrownBy(()->guarded(flyway)).hasMessageContaining("STOP_V7_STRUCTURE");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1",Integer.class)).isEqualTo(8);
    }
    @Test void unsafePolicyIsBlockedAndReal1419PartialFailureIsPreserved(){
        grant(true);int original=root.queryForObject("SELECT @@log_bin_trust_function_creators",Integer.class);
        try{
            root.execute("SET GLOBAL log_bin_trust_function_creators=0");
            assertThatThrownBy(()->guarded(migration(null))).hasMessageContaining("STOP_TRIGGER_POLICY");
            assertThat(db.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",Integer.class)).isZero();
            migration("6").migrate();
            assertThatThrownBy(()->migration(null).migrate()).hasStackTraceContaining("1419");
            assertThat(db.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='7' AND success=0",Integer.class)).isEqualTo(1);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('staff_session_permission','admin_audit_log')",Integer.class)).isEqualTo(2);
        }finally{root.execute("SET GLOBAL log_bin_trust_function_creators="+original);}
        assertThatThrownBy(()->guarded(migration(null))).hasMessageContaining("STOP_FAILED_MIGRATION");
    }
}
