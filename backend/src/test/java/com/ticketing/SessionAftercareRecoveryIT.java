package com.ticketing;

import com.ticketing.dto.CreateOrderRequest;
import com.ticketing.dto.RegisterRequest;
import com.ticketing.mapper.PurchaseRequestMapper;
import com.ticketing.service.AccountService;
import com.ticketing.service.OrderService;
import com.ticketing.service.PurchaseAdmission;
import com.ticketing.service.PurchaseOrderWorker;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

/** 关闭首个应用后由新实例调度恢复，不调用善后方法伪造重启恢复。 */
class SessionAftercareRecoveryIT {
    @Test
    void restartRecoversPaidAndPendingOrdersOfDisabledBuyerAfterSessionCancellation() throws Exception {
        byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
        String keys = "{\"1\":\"" + Base64.getEncoder().encodeToString(bytes) + "\"}";
        JdbcTemplate cleanup = null;
        long userId = 0, activityId = 0, sessionId = 0, skuId = 0;
        String paid, pending;
        try {
            try (var first = start(false, keys)) {
                DataSourceProperties source = first.getBean(DataSourceProperties.class);
                cleanup = new JdbcTemplate(new DriverManagerDataSource(source.getUrl(),source.getUsername(),source.getPassword()));
                JdbcTemplate db = first.getBean(JdbcTemplate.class);
                String suffix = UUID.randomUUID().toString().replace("-","");
                userId = Long.parseLong(first.getBean(AccountService.class).register(new RegisterRequest("acr_"+suffix.substring(0,20),UUID.randomUUID().toString())).id());
                db.update("INSERT INTO activity(title,category,city,venue,status,published_at) VALUES(?,'CONCERT','上海','验收场馆','PUBLISHED',UTC_TIMESTAMP(6))","善后重启"+suffix);
                activityId = db.queryForObject("SELECT id FROM activity WHERE title=?",Long.class,"善后重启"+suffix);
                db.update("INSERT INTO activity_session(activity_id,title,starts_at,ends_at,sale_starts_at) VALUES(?,'善后重启场次',TIMESTAMPADD(DAY,1,UTC_TIMESTAMP(6)),TIMESTAMPADD(DAY,2,UTC_TIMESTAMP(6)),TIMESTAMPADD(DAY,-1,UTC_TIMESTAMP(6)))",activityId);
                sessionId = db.queryForObject("SELECT id FROM activity_session WHERE activity_id=?",Long.class,activityId);
                db.update("INSERT INTO ticket_sku(session_id,name,price_cents,total_stock,sale_starts_at,sale_ends_at,status) VALUES(?,'重启善后票',100,10,TIMESTAMPADD(HOUR,-1,UTC_TIMESTAMP(6)),TIMESTAMPADD(HOUR,1,UTC_TIMESTAMP(6)),'ON_SALE')",sessionId);
                skuId = db.queryForObject("SELECT id FROM ticket_sku WHERE session_id=?",Long.class,sessionId);
                OrderService orders = first.getBean(OrderService.class);
                var admission=first.getBean(com.ticketing.service.PurchaseAdmission.class);
                var worker=first.getBean(com.ticketing.service.PurchaseOrderWorker.class);
                var requests=first.getBean(com.ticketing.mapper.PurchaseRequestMapper.class);
                paid = PurchaseTestSupport.order(worker,requests,db,Long.parseLong(admission.accept(userId,Long.toString(skuId),1,"paid-"+suffix).requestId()));
                orders.pay(userId,Long.parseLong(paid));
                pending = PurchaseTestSupport.order(worker,requests,db,Long.parseLong(admission.accept(userId,Long.toString(skuId),1,"pending-"+suffix).requestId()));
                db.update("UPDATE user_account SET status='DISABLED' WHERE id=?",userId);
                db.update("UPDATE activity_session SET status='CANCELLED' WHERE id=?",sessionId);
            }
            try (var second = start(true,keys)) {
                JdbcTemplate db = second.getBean(JdbcTemplate.class);
                long deadline = System.nanoTime()+Duration.ofSeconds(15).toNanos();
                // 每单独立提交，第一笔退款完成不代表同轮的第二笔取消已经提交。
                while (db.queryForObject("SELECT COUNT(*) FROM ticket_order WHERE (id=? AND status='REFUNDED') OR (id=? AND status='CANCELLED')",
                        Integer.class,Long.parseLong(paid),Long.parseLong(pending)) != 2 && System.nanoTime()<deadline) Thread.sleep(100);
                assertThat(db.queryForObject("SELECT status FROM ticket_order WHERE id=?",String.class,Long.parseLong(paid))).isEqualTo("REFUNDED");
                assertThat(db.queryForObject("SELECT status FROM ticket_order WHERE id=?",String.class,Long.parseLong(pending))).isEqualTo("CANCELLED");
                assertThat(db.queryForObject("SELECT locked_stock+sold_stock FROM ticket_sku WHERE id=?",Integer.class,skuId)).isZero();
                assertThat(db.queryForObject("SELECT COUNT(*) FROM refund_record WHERE order_id=? AND operator_id IS NULL",Integer.class,Long.parseLong(paid))).isEqualTo(1);
                assertThat(db.queryForObject("SELECT COUNT(*) FROM electronic_ticket WHERE order_id=? AND status='VOID'",Integer.class,Long.parseLong(paid))).isEqualTo(1);
                assertThat(db.queryForObject("SELECT COUNT(*) FROM payment_record WHERE order_id=? AND status='SUCCEEDED'",Integer.class,Long.parseLong(paid))).isEqualTo(1);
            }
        } finally {
            if (cleanup != null) {
                PurchaseTestSupport.cleanup(cleanup,sessionId);
                cleanup.update("DELETE e FROM outbox_event e JOIN ticket_sku s ON e.aggregate_id=s.id WHERE e.aggregate_type='TICKET_SKU' AND s.session_id=?",sessionId);
                for (String table: List.of("order_aftercare","refund_record","electronic_ticket","payment_record","ticket_order_item")) cleanup.update("DELETE t FROM "+table+" t JOIN ticket_order o ON o.id=t.order_id WHERE o.session_id=?",sessionId);
                cleanup.update("DELETE FROM ticket_order WHERE session_id=?",sessionId);
                cleanup.update("DELETE FROM ticket_sku WHERE id=?",skuId);
                cleanup.update("DELETE FROM activity_session WHERE id=?",sessionId);
                cleanup.update("DELETE FROM activity WHERE id=?",activityId);
                cleanup.update("DELETE FROM user_account WHERE id=? AND id NOT IN (SELECT operator_id FROM admin_audit_log)",userId);
            }
        }
    }

    private ConfigurableApplicationContext start(boolean enabled,String keys) {
        return new SpringApplicationBuilder(TicketingApplication.class).run("--server.port=0",
                "--ticketing.admin-bootstrap.enabled=false","--ticketing.checkin.credential-keys="+keys,
                "--ticketing.order.aftercare.enabled="+enabled,"--ticketing.order.aftercare.scan-delay-ms=100",
                "--ticketing.order.aftercare.batch-size=100","--spring.main.banner-mode=off");
    }
}
