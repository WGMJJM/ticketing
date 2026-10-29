package com.ticketing.service;

import com.ticketing.entity.PurchaseRequest;
import com.ticketing.entity.WaitlistEntry;
import com.ticketing.mapper.WaitlistMapper;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 统一消费者的候补参与者，不拥有库存和建单逻辑。 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class WaitlistPurchaseLifecycle implements PurchaseLifecycle {
    private final WaitlistMapper waitlists;
    private final TradeResourceLocker resources;
    private final InventoryVersionService versions;
    public WaitlistPurchaseLifecycle(WaitlistMapper waitlists, TradeResourceLocker resources, InventoryVersionService versions) {
        this.waitlists = waitlists; this.resources = resources; this.versions = versions;
    }

    @Override
    public void validate(PurchaseRequest request) {
        if (request.waitlistEntryId() == null) return;
        // SKU 锁已串行化队首变化，不提前锁候补行，保持订单→候补锁序。
        WaitlistEntry head = waitlists.head(request.skuId());
        if (head == null || !head.getId().equals(request.waitlistEntryId())
                || !"PROCESSING".equals(head.getStatus()) || head.getUserId() != request.userId()
                || head.getQuantity() != request.quantity() || !head.purchaseKey().equals(request.idempotencyKey())
                || !Objects.equals(head.getPurchaseRequestId(), request.id())) {
            throw new IllegalStateException("候补请求与当前队首不一致，停止受理并等待核对");
        }
    }

    @Override
    public void allocated(PurchaseRequest result) {
        if (result.waitlistEntryId() != null) {
            if (waitlists.allocate(result.waitlistEntryId(), result.id(), result.orderId()) != 1) {
                throw new IllegalStateException("候补分配关联未提交");
            }
            versions.changed(result.skuId());
        }
    }
    @Override
    public void failed(PurchaseRequest result) {
        if (result.waitlistEntryId() == null) return;
        resources.lockWaitlistPurchase(result.userId(), result.skuId(), false);
        WaitlistEntry entry = waitlists.selectById(result.waitlistEntryId());
        if (entry == null || !"PROCESSING".equals(entry.getStatus())
                || !Objects.equals(entry.getPurchaseRequestId(), result.id())) {
            throw new IllegalStateException("候补失败结论与本次申请不一致");
        }
        boolean retryable = "TICKET_UNAVAILABLE".equals(result.failureCode())
                || "STOCK_UNAVAILABLE".equals(result.failureCode()) || "QUEUE_EXPIRED".equals(result.failureCode());
        if (!retryable || !entry.getExpiresAt().isAfter(waitlists.databaseNow())) {
            waitlists.expireProcessing(entry.getId(), result.id());
            versions.changed(result.skuId());
        }
        // 可重试失败保持 PROCESSING，结算成功后调度器才允许推进尝试号。
        // 失效队首的 Redis waitlistPending 同样阻止下一位预扣/关闭门禁，直至结算完成。
    }
}
