package com.ticketing;

import com.ticketing.config.AftercareProperties;
import com.ticketing.dto.RegisterRequest;
import com.ticketing.enums.RefundReason;
import com.ticketing.mapper.AftercareMapper;
import com.ticketing.mapper.ElectronicTicketMapper;
import com.ticketing.mapper.PurchaseRequestMapper;
import com.ticketing.mapper.RefundMapper;
import com.ticketing.service.AccountService;
import com.ticketing.service.OrderRefundService;
import com.ticketing.service.OrderService;
import com.ticketing.service.PurchaseOrderWorker;
import com.ticketing.service.WaitlistDispatcher;
import com.ticketing.task.SessionAftercareJob;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真实Cookie/CSRF与MySQL独立连接竞争，并核对最终库存、支付、票券及售后事实。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.servlet.session.cookie.secure=false", "ticketing.admin-bootstrap.enabled=false"})
class OrderAftercareIT {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired AccountService accounts;
    @Autowired OrderRefundService service;
    @Autowired OrderService orders;
    @Autowired com.ticketing.service.PurchaseOrderWorker purchaseWorker;
    @Autowired com.ticketing.mapper.PurchaseRequestMapper purchaseRequests;
    @Autowired AftercareMapper aftercare;
    @Autowired WaitlistDispatcher dispatcher;
    @MockitoSpyBean RefundMapper refunds;
    @MockitoSpyBean ElectronicTicketMapper tickets;
    final List<Long> users = new ArrayList<>();
    long activityId, sessionId, skuId;
    Client buyer, admin, secondAdmin;

    @BeforeEach
    void fixture() throws Exception {
        buyer = client(false); admin = client(true); secondAdmin = client(true);
        String title = "售后验收" + UUID.randomUUID();
        jdbc.update("INSERT INTO activity(title,category,city,venue,status,published_at) VALUES(?,'CONCERT','上海','售后验收场馆','PUBLISHED',UTC_TIMESTAMP(6))", title);
        activityId = jdbc.queryForObject("SELECT id FROM activity WHERE title=?", Long.class, title);
        jdbc.update("INSERT INTO activity_session(activity_id,title,starts_at,ends_at,sale_starts_at) VALUES(?,'售后测试场次',TIMESTAMPADD(MINUTE,30,UTC_TIMESTAMP(6)),TIMESTAMPADD(HOUR,2,UTC_TIMESTAMP(6)),TIMESTAMPADD(DAY,-1,UTC_TIMESTAMP(6)))", activityId);
        sessionId = jdbc.queryForObject("SELECT id FROM activity_session WHERE activity_id=?", Long.class, activityId);
        jdbc.update("INSERT INTO ticket_sku(session_id,name,price_cents,total_stock,sale_starts_at,sale_ends_at,status) VALUES(?,'售后票档',12501,20,TIMESTAMPADD(HOUR,-1,UTC_TIMESTAMP(6)),TIMESTAMPADD(MINUTE,20,UTC_TIMESTAMP(6)),'ON_SALE')", sessionId);
        skuId = jdbc.queryForObject("SELECT id FROM ticket_sku WHERE session_id=?", Long.class, sessionId);
    }

    @AfterEach
    void cleanup() {
        reset(refunds, tickets);
        if (sessionId != 0) {
            PurchaseTestSupport.cleanup(jdbc, sessionId);
            jdbc.update("DELETE e FROM outbox_event e JOIN ticket_sku s ON e.aggregate_id=s.id WHERE e.aggregate_type='TICKET_SKU' AND s.session_id=?", sessionId);
            for (String table : List.of("order_aftercare", "refund_record", "waitlist_entry", "electronic_ticket", "payment_record", "ticket_order_item")) {
                String link = table.equals("waitlist_entry") ? "reserved_order_id" : "order_id";
                jdbc.update("DELETE t FROM " + table + " t JOIN ticket_order o ON o.id=t." + link + " WHERE o.session_id=?", sessionId);
            }
            jdbc.update("DELETE w FROM waitlist_entry w JOIN ticket_sku s ON w.sku_id=s.id WHERE s.session_id=?", sessionId);
            jdbc.update("DELETE FROM ticket_order WHERE session_id=?", sessionId);
            jdbc.update("DELETE FROM ticket_sku WHERE session_id=?", sessionId);
            jdbc.update("DELETE FROM activity_session WHERE id=?", sessionId);
            jdbc.update("DELETE FROM activity WHERE id=?", activityId);
        }
        for (long id : users) jdbc.update("DELETE FROM user_account WHERE id=? AND id NOT IN (SELECT operator_id FROM admin_audit_log)", id);
    }

    @Test
    void fullRefundPreservesPaymentSnapshotAndVoidsEveryUnusedTicket() throws Exception {
        long id = paid(buyer, 2);
        var before = jdbc.queryForMap("SELECT * FROM payment_record WHERE order_id=?", id);
        JsonNode result = refund(admin, id).path("data");
        assertThat(result.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(result.path("refund").path("amountCents").asText()).isEqualTo("25002");
        assertThat(result.path("refund").path("operatorId").asText()).isEqualTo(Long.toString(admin.id));
        assertThat(jdbc.queryForMap("SELECT * FROM payment_record WHERE order_id=?", id)).isEqualTo(before);
        assertThat(state(id)).isEqualTo("REFUNDED"); stock(0,0);
        assertThat(count("electronic_ticket", "order_id=? AND status='VOID'", id)).isEqualTo(2);
        assertThat(count("ticket_order", "id=? AND paid_at IS NOT NULL", id)).isEqualTo(1);
        assertThat(count("outbox_event", "aggregate_id=? AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.reason'))='ORDER_REFUNDED'", skuId)).isEqualTo(1);
        assertThat(orders.limits(buyer.id, sessionId).usedQuantity()).isZero();
        JsonNode own = buyer.call("GET", "/orders/" + id, null, 200).path("data");
        assertThat(own.path("refund").path("simulated").asBoolean()).isTrue();
        assertThat(own.path("canCancel").asBoolean()).isFalse();
        assertThat(own.path("canPay").asBoolean()).isFalse();
        assertThat(admin.call("GET", "/admin/orders/" + id, null, 200).path("data").path("tickets").path("voided").asInt()).isEqualTo(2);
    }

    @Test
    void repeatedAndConcurrentAdministratorsRefundOnlyOnce() throws Exception {
        long id = paid(buyer, 1);
        var results = parallel(8, index -> refund(index % 2 == 0 ? admin : secondAdmin, id));
        String original = results.getFirst().path("data").path("refund").path("refundNo").asText();
        for (JsonNode result : results) assertThat(result.path("data").path("refund").path("refundNo").asText()).isEqualTo(original);
        assertThat(refund(admin, id).path("data").path("refund").path("refundNo").asText()).isEqualTo(original);
        assertThat(count("refund_record", "order_id=?", id)).isEqualTo(1); stock(0,0);
        assertThat(count("outbox_event", "aggregate_id=?", skuId)).isEqualTo(1);
    }

    @Test
    void usedTicketCreatesPersistentManualStateWithoutChangingHistory() throws Exception {
        long id = paid(buyer, 2);
        String credential = credential(id);
        admin.call("POST", "/admin/checkins", Map.of("credential", credential), 200);
        var before = jdbc.queryForList("SELECT id,status,used_at,checked_by FROM electronic_ticket WHERE order_id=? ORDER BY id", id);
        assertThat(refund(admin,id).path("data").path("status").asText()).isEqualTo("MANUAL_REQUIRED");
        assertThat(aftercare.find(id).reasonCode()).isEqualTo("TICKET_ALREADY_USED");
        assertThat(jdbc.queryForList("SELECT id,status,used_at,checked_by FROM electronic_ticket WHERE order_id=? ORDER BY id", id)).isEqualTo(before);
        assertThat(state(id)).isEqualTo("PAID"); stock(0,2);
        assertThat(count("refund_record", "order_id=?", id)).isZero();
        jdbc.update("UPDATE activity_session SET status='CANCELLED' WHERE id=?", sessionId);
        service.processCancelledSession(id);
        assertThat(aftercare.find(id).status()).isEqualTo("MANUAL_REQUIRED");
    }

    @Test
    void missingOrInvalidTicketsRequireManualTreatment() throws Exception {
        long id = paid(buyer, 2);
        jdbc.update("DELETE FROM electronic_ticket WHERE order_id=? AND sequence_no=2", id);
        assertThat(refund(admin,id).path("data").path("status").asText()).isEqualTo("MANUAL_REQUIRED");
        assertThat(aftercare.find(id).reasonCode()).isEqualTo("TICKET_RELATION_INVALID");
        assertThat(state(id)).isEqualTo("PAID"); stock(0,2);
        assertThat(count("electronic_ticket", "order_id=? AND status='UNUSED'", id)).isEqualTo(1);
    }

    @Test
    void refundAndCheckinHaveOnlyOneBusinessEffect() throws Exception {
        long id = paid(buyer, 1);
        String credential = credential(id);
        parallel(2, index -> index == 0 ? refund(admin,id)
                : secondAdmin.call("POST", "/admin/checkins", Map.of("credential",credential), -1));
        if (state(id).equals("REFUNDED")) {
            stock(0,0);
            assertThat(count("electronic_ticket", "order_id=? AND status='VOID'", id)).isEqualTo(1);
            assertThat(count("refund_record", "order_id=?", id)).isEqualTo(1);
        } else {
            assertThat(state(id)).isEqualTo("PAID"); stock(0,1);
            assertThat(count("electronic_ticket", "order_id=? AND status='USED'", id)).isEqualTo(1);
            assertThat(count("refund_record", "order_id=?", id)).isZero();
            assertThat(aftercare.find(id).status()).isEqualTo("MANUAL_REQUIRED");
        }
    }

    @Test
    void refundPaymentCancelAndExpiryCompetitionDoesNotRepeatStockEffects() throws Exception {
        long id = paid(buyer, 1);
        parallel(4, index -> switch (index) {
            case 0 -> refund(admin,id);
            case 1 -> buyer.call("POST", "/orders/"+id+"/pay", null,-1);
            case 2 -> buyer.call("POST", "/orders/"+id+"/cancel",null,409);
            default -> orders.expire(buyer.id,id);
        });
        assertThat(state(id)).isEqualTo("REFUNDED"); stock(0,0);
        assertThat(count("payment_record", "order_id=? AND status='SUCCEEDED'",id)).isEqualTo(1);
        assertThat(count("refund_record", "order_id=?",id)).isEqualTo(1);
    }

    @Test
    void failureAfterTicketVoidingRollsBackEveryWrite() throws Exception {
        long id = paid(buyer,2);
        doThrow(new IllegalStateException("注入模拟退款写入失败"))
                .when(refunds).insert(eq(id),anyString(),any(),any(),any());
        admin.call("POST", "/admin/orders/"+id+"/refund",Map.of("reason","CUSTOMER_REQUEST"),500);
        assertThat(state(id)).isEqualTo("PAID"); stock(0,2);
        assertThat(count("electronic_ticket", "order_id=? AND status='UNUSED'",id)).isEqualTo(2);
        assertThat(count("refund_record", "order_id=?",id)).isZero();
        assertThat(count("order_aftercare", "order_id=?",id)).isZero();
        assertThat(count("outbox_event", "aggregate_id=?",skuId)).isZero();
        reset(refunds); refund(admin,id); stock(0,0);
    }

    @Test
    void affectedTicketCountMismatchRollsBackRefundAndInventory() throws Exception {
        long id = paid(buyer,1);
        doReturn(0).when(tickets).voidUnused(id);
        admin.call("POST", "/admin/orders/"+id+"/refund",Map.of("reason","CUSTOMER_REQUEST"),409);
        assertThat(state(id)).isEqualTo("PAID"); stock(0,1);
        assertThat(count("refund_record", "order_id=?",id)).isZero();
        assertThat(count("electronic_ticket", "order_id=? AND status='UNUSED'",id)).isEqualTo(1);
    }

    @Test
    void completedWaitlistRemainsCompletedAfterRefund() throws Exception {
        jdbc.update("UPDATE ticket_sku SET total_stock=0 WHERE id=?",skuId);
        JsonNode entry=buyer.call("POST","/waitlist",Map.of("skuId",Long.toString(skuId),"quantity",1),200).path("data");
        jdbc.update("UPDATE ticket_sku SET total_stock=2 WHERE id=?",skuId);
        dispatcher.dispatch(skuId);
        long id=jdbc.queryForObject("SELECT reserved_order_id FROM waitlist_entry WHERE id=?",Long.class,entry.path("id").asLong());
        buyer.call("POST","/orders/"+id+"/pay",null,200);
        refund(admin,id);
        assertThat(jdbc.queryForObject("SELECT status FROM waitlist_entry WHERE id=?",String.class,entry.path("id").asLong())).isEqualTo("COMPLETED");
        assertThat(count("waitlist_entry","reserved_order_id=? AND sku_id="+skuId,id)).isEqualTo(1); stock(0,0);
    }

    @Test
    void cancelledSessionProcessesBoundedBatchesAndDisabledUsers() throws Exception {
        long first=paid(buyer,1);
        long second=buyer.create(1);
        Client extra=client(false);
        long third=paid(extra,2);
        long fourth=extra.create(1);
        jdbc.update("UPDATE user_account SET status='DISABLED' WHERE id=?",buyer.id);
        admin.call("POST","/admin/sessions/"+sessionId+"/cancel",Map.of("version",0),200);
        assertThat(state(first)).isEqualTo("PAID");
        var job=new SessionAftercareJob(aftercare,service,new AftercareProperties(2,3,1));
        job.scan();
        assertThat(count("order_aftercare","order_id IN (?,"+second+","+third+","+fourth+")",first)).isEqualTo(2);
        job.scan(); job.scan();
        assertThat(state(first)).isEqualTo("REFUNDED"); assertThat(state(third)).isEqualTo("REFUNDED");
        assertThat(state(second)).isEqualTo("CANCELLED"); assertThat(state(fourth)).isEqualTo("CANCELLED"); stock(0,0);
        assertThat(orders.limits(buyer.id,sessionId).usedQuantity()).isZero();
        assertThat(orders.limits(extra.id,sessionId).usedQuantity()).isZero();
        parallel(4, i -> { service.processCancelledSession(first); service.processCancelledSession(second); return true; });
        assertThat(count("refund_record","order_id=?",first)).isEqualTo(1); stock(0,0);
    }

    @Test
    void failedOrderBacksOffWithoutBlockingLaterOrdersAndEventuallyBecomesManual() throws Exception {
        long first=paid(buyer,1); long second=buyer.create(1);
        jdbc.update("UPDATE activity_session SET status='CANCELLED' WHERE id=?",sessionId);
        doThrow(new IllegalStateException("注入持久失败")).when(refunds).insert(eq(first),anyString(),any(),any(),any());
        var job=new SessionAftercareJob(aftercare,service,new AftercareProperties(10,2,30));
        job.scan();
        assertThat(state(first)).isEqualTo("PAID"); assertThat(state(second)).isEqualTo("CANCELLED"); stock(0,1);
        assertThat(aftercare.find(first).status()).isEqualTo("RETRY");
        assertThat(aftercare.find(first).nextRetryAt()).isAfter(aftercare.find(first).lastAttemptAt());
        job.scan(); assertThat(aftercare.find(first).attemptCount()).isEqualTo(1);
        jdbc.update("UPDATE order_aftercare SET next_retry_at=UTC_TIMESTAMP(6) WHERE order_id=?",first);
        job.scan(); assertThat(aftercare.find(first).status()).isEqualTo("MANUAL_REQUIRED");
        reset(refunds); job.scan(); assertThat(state(first)).isEqualTo("PAID");
        refund(admin,first); assertThat(state(first)).isEqualTo("REFUNDED"); stock(0,0);
    }

    @Test
    void multipleWorkersSettleThirtyTwoOrdersInBoundedBatchesWithoutRepeatedEffects() throws Exception {
        jdbc.update("UPDATE ticket_sku SET total_stock=64 WHERE id=?",skuId);
        for (int user=0;user<8;user++) {
            Client customer=client(false);
            for (int item=0;item<4;item++) {
                long id=customer.create(1);
                if (item<2) customer.call("POST","/orders/"+id+"/pay",null,200);
            }
        }
        stock(16,16);
        admin.call("POST","/admin/sessions/"+sessionId+"/cancel",Map.of("version",0),200);
        var job=new SessionAftercareJob(aftercare,service,new AftercareProperties(7,3,1));
        job.scan();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_aftercare c JOIN ticket_order o ON o.id=c.order_id WHERE o.session_id=?",Integer.class,sessionId)).isEqualTo(7);
        parallel(4,index -> {
            var worker=new SessionAftercareJob(aftercare,service,new AftercareProperties(7,3,1));
            for (int turn=0;turn<6;turn++) worker.scan();
            return true;
        });
        stock(0,0);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_order WHERE session_id=? AND status='REFUNDED'",Integer.class,sessionId)).isEqualTo(16);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_order WHERE session_id=? AND status='CANCELLED'",Integer.class,sessionId)).isEqualTo(16);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refund_record r JOIN ticket_order o ON o.id=r.order_id WHERE o.session_id=?",Integer.class,sessionId)).isEqualTo(16);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM electronic_ticket t JOIN ticket_order o ON o.id=t.order_id WHERE o.session_id=? AND t.status='VOID'",Integer.class,sessionId)).isEqualTo(16);
    }

    @Test
    void offlineActivityDoesNotTriggerRefunds() throws Exception {
        long paid=paid(buyer,1); long pending=buyer.create(1);
        jdbc.update("UPDATE activity SET status='OFFLINE' WHERE id=?",activityId);
        new SessionAftercareJob(aftercare,service,new AftercareProperties(100,3,1)).scan();
        service.processCancelledSession(paid); service.processCancelledSession(pending);
        assertThat(state(paid)).isEqualTo("PAID"); assertThat(state(pending)).isEqualTo("PENDING"); stock(1,1);
        assertThat(count("refund_record","order_id=?",paid)).isZero();
    }

    @Test
    void missingOrderItemIsPersistedAsManualAndRemainsVisibleToAdministrators() throws Exception {
        long id = buyer.create(1);
        jdbc.update("DELETE FROM ticket_order_item WHERE order_id=?",id);
        jdbc.update("UPDATE activity_session SET status='CANCELLED' WHERE id=?",sessionId);
        service.processCancelledSession(id);
        assertThat(aftercare.find(id).reasonCode()).isEqualTo("ORDER_ITEM_MISSING");
        assertThat(aftercare.find(id).status()).isEqualTo("MANUAL_REQUIRED");
        JsonNode detail = admin.call("GET","/admin/orders/"+id,null,200).path("data");
        assertThat(detail.path("summary").path("order").path("quantity").isNull()).isTrue();
        assertThat(detail.path("aftercare").path("status").asText()).isEqualTo("MANUAL_REQUIRED");
        assertThat(state(id)).isEqualTo("PENDING"); stock(1,0);
    }

    @Test
    void administratorFiltersAreValidatedStableAndContainNoCredentialMaterial() throws Exception {
        long id=paid(buyer,1);
        String orderNo=jdbc.queryForObject("SELECT order_no FROM ticket_order WHERE id=?",String.class,id);
        String path="/admin/orders?userId="+buyer.id+"&sessionId="+sessionId+"&activityId="+activityId+"&status=PAID&orderNo="+orderNo+"&page=1&size=1";
        JsonNode result=admin.call("GET",path,null,200).path("data");
        assertThat(result.path("total").asInt()).isEqualTo(1);
        assertThat(result.path("items").get(0).path("order").path("id").asLong()).isEqualTo(id);
        assertThat(result.path("items").get(0).path("userId").isTextual()).isTrue();
        JsonNode detail=admin.call("GET","/admin/orders/"+id,null,200);
        assertThat(admin.call("GET", "/admin/orders?username=" + buyer.username
                + "&createdFrom=2000-01-01T00:00:00Z&createdTo=2100-01-01T00:00:00Z",null,200)
                .path("data").path("total").asInt()).isEqualTo(1);
        assertThat(detail.toString()).doesNotContain("credential", "digest", "password", "TKT1.");
        for (String q : List.of("page=0","size=51","userId=-1","status=BOGUS","createdFrom=2026-01-02T00:00:00Z&createdTo=2026-01-01T00:00:00Z")) admin.call("GET","/admin/orders?"+q,null,400);
        assertThat(admin.call("GET","/admin/orders?username=%27%20OR%201%3D1",null,200).path("data").path("total").asInt()).isZero();
    }

    @Test
    void currentRoleSessionCsrfAndReadonlyFieldsProtectRefund() throws Exception {
        long id=paid(buyer,1);
        new Client().call("GET","/admin/orders",null,401);
        Client anonymous = new Client(); anonymous.csrf();
        anonymous.call("POST","/admin/orders/"+id+"/refund",Map.of("reason","CUSTOMER_REQUEST"),401);
        buyer.call("GET","/admin/orders/"+id,null,403);
        buyer.call("POST","/admin/orders/"+id+"/refund",Map.of("reason","CUSTOMER_REQUEST"),403);
        String valid=admin.token; admin.token="";
        admin.call("POST","/admin/orders/"+id+"/refund",Map.of("reason","CUSTOMER_REQUEST"),403);
        admin.token="wrong";
        admin.call("POST","/admin/orders/"+id+"/refund",Map.of("reason","CUSTOMER_REQUEST"),403); admin.token=valid;
        for (String field:List.of("amountCents","userId","operatorId","status")) admin.call("POST","/admin/orders/"+id+"/refund",Map.of("reason","CUSTOMER_REQUEST",field,"1"),400);
        admin.call("POST","/admin/orders/"+id+"/refund",Map.of("reason","OTHER"),400);
        admin.call("POST","/admin/orders/"+id+"/refund",Map.of("reason","SESSION_CANCELLED"),409);
        jdbc.update("UPDATE user_account SET role='USER' WHERE id=?",admin.id);
        admin.call("GET","/admin/orders",null,403);
        admin.call("POST","/admin/orders/"+id+"/refund",Map.of("reason","CUSTOMER_REQUEST"),403);
        assertThat(state(id)).isEqualTo("PAID"); stock(0,1);
    }

    @Test
    void administratorCanRefundOwnPurchaseWithoutDuplicatingAccountLocks() throws Exception {
        long id = paid(admin,1);
        assertThat(refund(admin,id).path("data").path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(state(id)).isEqualTo("REFUNDED"); stock(0,0);
    }

    @Test
    void pendingCancelledAndExpiredOrdersCannotRefund() throws Exception {
        long id=buyer.create(1);
        admin.call("POST","/admin/orders/"+id+"/refund",Map.of("reason","CUSTOMER_REQUEST"),409);
        buyer.call("POST","/orders/"+id+"/cancel",null,200);
        admin.call("POST","/admin/orders/"+id+"/refund",Map.of("reason","CUSTOMER_REQUEST"),409);
        long expired=buyer.create(1);
        jdbc.update("UPDATE ticket_order SET created_at=TIMESTAMPADD(MINUTE,-20,UTC_TIMESTAMP(6)),expires_at=TIMESTAMPADD(MINUTE,-5,UTC_TIMESTAMP(6)) WHERE id=?",expired);
        orders.expire(buyer.id,expired);
        admin.call("POST","/admin/orders/"+expired+"/refund",Map.of("reason","CUSTOMER_REQUEST"),409); stock(0,0);
    }

    private JsonNode refund(Client operator,long id) throws Exception { return operator.call("POST","/admin/orders/"+id+"/refund",Map.of("reason","CUSTOMER_REQUEST"),200); }
    private long paid(Client client,int quantity) throws Exception { long id=client.create(quantity); client.call("POST","/orders/"+id+"/pay",null,200); return id; }
    private String credential(long id) throws Exception {
        long ticket=jdbc.queryForObject("SELECT MIN(id) FROM electronic_ticket WHERE order_id=?",Long.class,id);
        return buyer.call("GET","/tickets/"+ticket+"/credential",null,200).path("data").path("credential").asText();
    }
    private String state(long id) { return jdbc.queryForObject("SELECT status FROM ticket_order WHERE id=?",String.class,id); }
    private int count(String table,String where,long id) { return jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE "+where,Integer.class,id); }
    private void stock(int locked,int sold) {
        var row=jdbc.queryForMap("SELECT * FROM ticket_sku WHERE id=?",skuId);
        assertThat(row.get("locked_stock")).isEqualTo(locked); assertThat(row.get("sold_stock")).isEqualTo(sold);
        assertThat(locked).isNotNegative(); assertThat(sold).isNotNegative(); assertThat(locked+sold).isLessThanOrEqualTo((Integer)row.get("total_stock"));
    }
    private Client client(boolean administrator) throws Exception {
        Client client=new Client(); String username="af_"+UUID.randomUUID().toString().replace("-","").substring(0,24); String password=UUID.randomUUID().toString();
        client.csrf();
        if (administrator) accounts.initializeAdmin(new RegisterRequest(username,password));
        else client.call("POST","/auth/register",Map.of("username",username,"password",password),200);
        client.id=jdbc.queryForObject("SELECT id FROM user_account WHERE username=?",Long.class,username); users.add(client.id);
        client.username = username;
        client.raw("POST","/auth/login","username="+username+"&password="+URLEncoder.encode(password,StandardCharsets.UTF_8),"application/x-www-form-urlencoded",null,200);
        client.csrf(); return client;
    }
    private <T> List<T> parallel(int count,CheckedFunction<T> action) throws Exception {
        CountDownLatch start=new CountDownLatch(1);
        try (var pool=Executors.newFixedThreadPool(count)) {
            List<Future<T>> futures=new ArrayList<>();
            for (int index=0;index<count;index++) { int value=index; futures.add(pool.submit(()->{ start.await();return action.apply(value); })); }
            start.countDown(); List<T> results=new ArrayList<>();
            for (var future:futures) results.add(future.get(30,TimeUnit.SECONDS)); return results;
        }
    }
    @FunctionalInterface interface CheckedFunction<T> { T apply(int index) throws Exception; }
    private class Client {
        final HttpClient http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        long id; String token=""; String username;
        void csrf() throws Exception { token=call("GET","/auth/csrf",null,200).path("data").path("token").asText(); }
        long create(int quantity) throws Exception {
            long requestId=raw("POST","/orders",json.writeValueAsString(Map.of("skuId",Long.toString(skuId),"quantity",quantity)),"application/json",UUID.randomUUID().toString(),202).path("data").path("requestId").asLong();
            return Long.parseLong(PurchaseTestSupport.order(purchaseWorker,purchaseRequests,jdbc,requestId));
        }
        JsonNode call(String method,String path,Object body,int expected) throws Exception { return raw(method,path,body==null?"":json.writeValueAsString(body),"application/json",null,expected); }
        JsonNode raw(String method,String path,String body,String type,String key,int expected) throws Exception {
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+path)).timeout(Duration.ofSeconds(25)).header("Content-Type",type);
            if (!token.isEmpty()) request.header("X-CSRF-TOKEN",token);
            if (key!=null) request.header("Idempotency-Key",key);
            var response=http.send(request.method(method,HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
            if (expected>=0) assertThat(response.statusCode()).as("%s %s %s",method,path,response.body()).isEqualTo(expected);
            return json.readTree(response.body());
        }
    }
}
