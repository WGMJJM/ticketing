package com.ticketing;

import com.ticketing.admission.PurchaseRequest;
import com.ticketing.admission.PurchaseRequestMapper;
import com.ticketing.trade.PurchaseOrderWorker;
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
        db.update("UPDATE purchase_capacity SET pending_count=pending_count-(SELECT COUNT(*) FROM purchase_request p JOIN ticket_sku s ON p.sku_id=s.id WHERE s.session_id=? AND p.status IN('QUEUED','PROCESSING')) WHERE id=1",sessionId);
        db.update("DELETE e FROM outbox_event e JOIN purchase_request p ON e.aggregate_id=p.id JOIN ticket_sku s ON p.sku_id=s.id WHERE e.aggregate_type='PURCHASE_REQUEST' AND s.session_id=?",sessionId);
        db.update("DELETE p FROM purchase_request p JOIN ticket_sku s ON p.sku_id=s.id WHERE s.session_id=?",sessionId);
    }
}
