package com.ticketing.inventory;

import com.ticketing.common.BusinessException;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

/** SKU权威库存的事务写入口；调用方先遵守统一用户及父级锁序。 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class TicketInventory {
    private final TicketSkuMapper tickets;

    public TicketInventory(TicketSkuMapper tickets) {
        this.tickets = tickets;
    }

    /** 独立短读事务及时释放连接；不锁库存，消费者在统一锁序中最终复核。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, readOnly = true)
    public void requirePurchaseEligible(long skuId) {
        Integer eligible = tickets.purchaseEligibility(skuId);
        if (eligible == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND", "票档不存在，请刷新活动页面");
        }
        if (eligible != 1) {
            throw new BusinessException(HttpStatus.CONFLICT, "TICKET_UNAVAILABLE", "票档当前不在销售中，请刷新活动页面");
        }
    }

    public void reserve(long skuId, int quantity) {
        if (tickets.reserve(skuId, quantity) != 1) {
            throw new BusinessException(
                    HttpStatus.CONFLICT, "STOCK_UNAVAILABLE", "库存不足或销售窗口已结束，请刷新票档");
        }
    }

    public void release(long skuId, int quantity) {
        requireChanged(tickets.release(skuId, quantity));
    }

    public void sell(long skuId, int quantity) {
        requireChanged(tickets.sell(skuId, quantity));
    }

    public void refund(long skuId, int quantity) {
        requireChanged(tickets.refund(skuId, quantity));
    }

    private void requireChanged(int changed) {
        if (changed != 1) {
            throw new BusinessException(HttpStatus.CONFLICT, "STOCK_CONFLICT", "订单库存数据不一致，操作未生效");
        }
    }
}
