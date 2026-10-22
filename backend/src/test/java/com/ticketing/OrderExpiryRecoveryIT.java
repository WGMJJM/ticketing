package com.ticketing;

import com.ticketing.dto.RegisterRequest;
import com.ticketing.service.AccountService;
import com.ticketing.dto.CreateOrderRequest;
import com.ticketing.service.OrderService;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;

/** 真正关闭并重新启动应用上下文，由重启后的Scheduling自动补偿，不直接调用关单方法。 */
class OrderExpiryRecoveryIT {
    @Test
    void applicationRestartAutomaticallyRecoversHistoricalExpiredOrderOfDisabledAccount() throws Exception {
        JdbcTemplate cleanup = null;
        long userId = 0;
        long activityId = 0;
        long sessionId = 0;
        long skuId = 0;
        String orderId;
        try {
            try (ConfigurableApplicationContext first = start(false)) {
                DataSourceProperties source = first.getBean(DataSourceProperties.class);
                cleanup = new JdbcTemplate(new DriverManagerDataSource(source.getUrl(), source.getUsername(), source.getPassword()));
                JdbcTemplate jdbc = first.getBean(JdbcTemplate.class);
                String suffix = UUID.randomUUID().toString().replace("-", "");
                userId = Long.parseLong(first.getBean(AccountService.class)
                        .register(new RegisterRequest("recover_" + suffix.substring(0, 20), UUID.randomUUID().toString())).id());
                String title = "重启补偿验收" + suffix;
                jdbc.update("INSERT INTO activity(title,category,city,venue,status,published_at) VALUES(?,'CONCERT','上海','测试场馆','PUBLISHED',UTC_TIMESTAMP(6))", title);
                activityId = jdbc.queryForObject("SELECT id FROM activity WHERE title=?", Long.class, title);
                jdbc.update("INSERT INTO activity_session(activity_id,title,starts_at,ends_at,sale_starts_at) VALUES(?,'重启测试',DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 DAY),DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 2 DAY),DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 DAY))", activityId);
                sessionId = jdbc.queryForObject("SELECT id FROM activity_session WHERE activity_id=?", Long.class, activityId);
                jdbc.update("INSERT INTO ticket_sku(session_id,name,price_cents,total_stock,sale_starts_at,sale_ends_at,status) VALUES(?,'重启补偿票',100,5,DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 HOUR),DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 HOUR),'ON_SALE')", sessionId);
                skuId = jdbc.queryForObject("SELECT id FROM ticket_sku WHERE session_id=?", Long.class, sessionId);
                orderId = first.getBean(OrderService.class).create(userId,
                        new CreateOrderRequest(Long.toString(skuId), 2), "restart-" + suffix).id();
                jdbc.update("UPDATE ticket_order SET created_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 MINUTE),expires_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 2 SECOND) WHERE id=?", Long.parseLong(orderId));
                jdbc.update("UPDATE user_account SET status='DISABLED' WHERE id=?", userId);
                assertThat(jdbc.queryForObject("SELECT status FROM ticket_order WHERE id=?", String.class, Long.parseLong(orderId))).isEqualTo("PENDING");
                assertThat(jdbc.queryForObject("SELECT locked_stock FROM ticket_sku WHERE id=?", Integer.class, skuId)).isEqualTo(2);
            }
            // 第一实例已关闭，第二实例启动读取同一数据库，任务自动发现已过截止的历史订单。
            try (ConfigurableApplicationContext second = start(true)) {
                JdbcTemplate jdbc = second.getBean(JdbcTemplate.class);
                long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
                while (!"EXPIRED".equals(jdbc.queryForObject("SELECT status FROM ticket_order WHERE id=?", String.class, Long.parseLong(orderId)))
                        && System.nanoTime() < deadline) Thread.sleep(100);
                assertThat(jdbc.queryForObject("SELECT status FROM ticket_order WHERE id=?", String.class, Long.parseLong(orderId))).isEqualTo("EXPIRED");
                assertThat(jdbc.queryForObject("SELECT locked_stock FROM ticket_sku WHERE id=?", Integer.class, skuId)).isZero();
                assertThat(jdbc.queryForObject("SELECT sold_stock FROM ticket_sku WHERE id=?", Integer.class, skuId)).isZero();
                assertThat(second.getBean(OrderService.class).limits(userId, sessionId).usedQuantity()).isZero();
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_record WHERE order_id=?", Integer.class, Long.parseLong(orderId))).isZero();
            }
        } finally {
            if (cleanup != null) {
                cleanup.update("DELETE e FROM outbox_event e JOIN ticket_sku s ON e.aggregate_id=s.id WHERE e.aggregate_type='TICKET_SKU' AND s.session_id=?",sessionId);
                cleanup.update("DELETE p FROM payment_record p JOIN ticket_order o ON o.id=p.order_id WHERE o.session_id=?", sessionId);
                cleanup.update("DELETE i FROM ticket_order_item i JOIN ticket_order o ON o.id=i.order_id WHERE o.session_id=?", sessionId);
                cleanup.update("DELETE FROM ticket_order WHERE session_id=?", sessionId);
                cleanup.update("DELETE FROM ticket_sku WHERE session_id=?", sessionId);
                cleanup.update("DELETE FROM activity_session WHERE id=?", sessionId);
                cleanup.update("DELETE FROM activity WHERE id=?", activityId);
                cleanup.update("DELETE FROM user_account WHERE id=?", userId);
            }
        }
    }

    private ConfigurableApplicationContext start(boolean expiryEnabled) {
        return new SpringApplicationBuilder(TicketingApplication.class).run(
                "--server.port=0", "--ticketing.admin-bootstrap.enabled=false",
                "--ticketing.order.expiry.enabled=" + expiryEnabled,
                "--ticketing.order.expiry.scan-delay-ms=100", "--ticketing.order.expiry.batch-size=100",
                "--spring.main.banner-mode=off");
    }
}
