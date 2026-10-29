package com.ticketing.service;

import com.ticketing.config.WaitlistProperties;
import com.ticketing.dto.PurchaseIntent;
import com.ticketing.entity.WaitlistEntry;
import com.ticketing.exception.BusinessException;
import com.ticketing.mapper.WaitlistMapper;
import org.springframework.stereotype.Service;

/** 短事务选队首，事务外调用唯一购票入口，未决申请始终挡住下一位。 */
@Service
public class WaitlistDispatcher {
    private final WaitlistMapper waitlists;
    private final WaitlistService service;
    private final StockSnapshotService inventory;
    private final PurchaseAdmission purchase;
    private final RedisStockReservationService stocks;
    private final int maxSteps;

    public WaitlistDispatcher(WaitlistMapper waitlists, WaitlistService service, StockSnapshotService inventory,
            PurchaseAdmission purchase, RedisStockReservationService stocks, WaitlistProperties properties) {
        this.waitlists = waitlists; this.service = service; this.inventory = inventory;
        this.purchase = purchase; this.stocks = stocks; this.maxSteps = properties.dispatchMaxSteps();
    }

    public int dispatch(long skuId) {
        if (inventory.needsRefresh(skuId)) inventory.synchronize(skuId);
        // 同步尚未就绪时只会原键重放；Lua 在幂等判断之后拒绝新的预扣。
        int submitted = 0;
        for (int step = 0; step < maxSteps; step++) {
            WaitlistEntry head = service.prepare(skuId);
            if (head == null) {
                if (waitlists.head(skuId) == null) { service.prepare(skuId); break; }
                continue;
            }
            var result = purchase.result(head.getPurchaseRequestId());
            if (result != null && !result.terminal()) break;
            if (result != null && result.terminal()) {
                if (!stocks.settle(result)) break;
                if (!"SUCCEEDED".equals(result.status())) {
                    boolean retryable = "STOCK_UNAVAILABLE".equals(result.failureCode())
                            || "TICKET_UNAVAILABLE".equals(result.failureCode()) || "QUEUE_EXPIRED".equals(result.failureCode());
                    service.finishAttempt(head.getId(), result.id(), retryable);
                    break;
                }
                continue;
            }
            try {
                purchase.submit(new PurchaseIntent(head.getUserId(), skuId, head.getQuantity(), "WAITLIST_CONFIRM",
                        head.purchaseKey(), head.getId(), head.getAttemptNo(), head.getExpiresAt(), head.getPurchaseRequestId()));
                submitted++;
            } catch (BusinessException exception) {
                // 明确没有预扣的业务拒绝才退出尝试；未决申请继续阻挡队首。
                String code = exception.getCode();
                if (("STOCK_UNAVAILABLE".equals(code) || "TICKET_UNAVAILABLE".equals(code)
                        || "PURCHASE_LIMIT".equals(code)) && purchase.result(head.getPurchaseRequestId()) == null) {
                    service.finishAttempt(head.getId(), head.getPurchaseRequestId(), "STOCK_UNAVAILABLE".equals(code));
                } else if (!"PURCHASE_QUEUE_FULL".equals(code) && !"PURCHASE_ADMISSION_UNAVAILABLE".equals(code)) {
                    throw exception;
                }
            }
            break;
        }
        return submitted;
    }

}
