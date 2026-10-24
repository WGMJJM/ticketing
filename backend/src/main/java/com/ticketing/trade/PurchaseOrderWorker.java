package com.ticketing.trade;

import com.ticketing.admission.PurchaseProcessing;
import com.ticketing.admission.PurchaseRequest;
import com.ticketing.common.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

@Service
public class PurchaseOrderWorker {
    private final PurchaseProcessing processing;
    private final TradeResourceLocker resources;
    private final OrderReservation reservation;
    private final TransactionTemplate transaction;
    private final MeterRegistry meters;
    public PurchaseOrderWorker(PurchaseProcessing processing,TradeResourceLocker resources,OrderReservation reservation,PlatformTransactionManager manager,MeterRegistry meters) {
        this.processing=processing; this.resources=resources; this.reservation=reservation;
        transaction=new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.meters=meters;
    }
    public void process(long id) {
        PurchaseRequest claim=processing.claim(id);
        if(claim==null) { processing.recover(id); return; }
        complete(claim);
    }
    /** 与认领分离，旧令牌执行也必须经过最终请求行CAS，失败使所有库存和订单写入回滚。 */
    public void complete(PurchaseRequest claim) {
        Timer.Sample sample=Timer.start(meters);
        String outcome="technical-failure";
        try {
            transaction.executeWithoutResult(status -> {
                TradeContext context=resources.lockReservation(claim.userId(),claim.skuId(),true);
                long orderId=reservation.reservePurchase(context,claim.quantity(),claim.idempotencyKey());
                if(!processing.lockValid(claim)) throw new IllegalStateException("购票处理资格已失效，回滚交易");
                processing.succeed(claim,orderId);
            });
            outcome="success";
        } catch(BusinessException exception) {
            if(!processing.deterministic(exception.getCode())) throw exception;
            // 预占尝试已完整回滚，失败结果在新事务复核令牌，避免rollback-only吞掉终态。
            processing.fail(claim,exception.getCode());
            outcome="business-failure";
        } finally {
            sample.stop(meters.timer("ticketing.trade.transaction.duration","outcome",outcome));
        }
    }
}
