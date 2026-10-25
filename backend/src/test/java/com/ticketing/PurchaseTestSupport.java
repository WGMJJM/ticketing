package com.ticketing;

import com.ticketing.entity.PurchaseRequest;
import com.ticketing.mapper.PurchaseRequestMapper;
import com.ticketing.service.PurchaseOrderWorker;

import java.time.Duration;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;

/** 历史交易回归显式驱动生产消费者服务，测试真实SQL事务；新MQ协议另由AsyncPurchaseIT验证。 */
public final class PurchaseTestSupport {
    private PurchaseTestSupport() {}
    public static PurchaseRequest resolve(PurchaseOrderWorker worker, PurchaseRequestMapper mapper, JdbcTemplate db, long id) throws Exception {
        worker.process(id);
        long end=System.nanoTime()+Duration.ofSeconds(15).toNanos();
        PurchaseRequest value;
        do {
            value=mapper.find(id);
            if(value.terminal()) {
                // 本工具仅隔离历史交易回归的已处理夹具消息，不伪造SENT；MQ端到端测试不使用本工具。
                db.update("DELETE FROM outbox_event WHERE aggregate_type='PURCHASE_REQUEST' AND aggregate_id=?",id);
                Long session=db.queryForObject("SELECT session_id FROM ticket_sku WHERE id=?",Long.class,value.skuId());
                assertQuotaConsistent(db,session);
                return value;
            }
            Thread.sleep(20);
        } while(System.nanoTime()<end);
        throw new AssertionError("显式驱动交易后请求未在限定时间内终结");
    }
    public static String order(PurchaseOrderWorker worker, PurchaseRequestMapper mapper, JdbcTemplate db, long id)throws Exception {
        PurchaseRequest result=resolve(worker,mapper,db,id);
        assertThat(result.status()).as("购票失败码 %s",result.failureCode()).isEqualTo("SUCCEEDED");
        return result.orderId().toString();
    }
    public static void cleanup(JdbcTemplate db,long sessionId) {
        db.update("DELETE FROM user_session_quota WHERE session_id=?",sessionId);
        db.update("UPDATE purchase_capacity SET pending_count=pending_count-(SELECT COUNT(*) FROM purchase_request p JOIN ticket_sku s ON p.sku_id=s.id WHERE s.session_id=? AND p.status IN('QUEUED','PROCESSING')) WHERE id=1",sessionId);
        db.update("DELETE e FROM outbox_event e JOIN purchase_request p ON e.aggregate_id=p.id JOIN ticket_sku s ON p.sku_id=s.id WHERE e.aggregate_type='PURCHASE_REQUEST' AND s.session_id=?",sessionId);
        db.update("DELETE p FROM purchase_request p JOIN ticket_sku s ON p.sku_id=s.id WHERE s.session_id=?",sessionId);
    }
    public static void assertQuotaConsistent(JdbcTemplate db,long sessionId) {
        assertThat(db.queryForObject("""
            SELECT COUNT(*) FROM user_session_quota q WHERE q.session_id=? AND q.occupied_quantity<>
            (SELECT COALESCE(SUM(i.quantity),0) FROM ticket_order o JOIN ticket_order_item i ON i.order_id=o.id
             WHERE o.user_id=q.user_id AND o.session_id=q.session_id AND o.status IN('PENDING','PAID'))
            """,Integer.class,sessionId)).isZero();
        assertThat(db.queryForObject("""
            SELECT COUNT(*) FROM ticket_order o LEFT JOIN user_session_quota q ON q.user_id=o.user_id AND q.session_id=o.session_id
            WHERE o.session_id=? AND o.status IN('PENDING','PAID') AND q.user_id IS NULL
            """,Integer.class,sessionId)).isZero();
    }
}
