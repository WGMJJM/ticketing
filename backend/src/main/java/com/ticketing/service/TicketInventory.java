package com.ticketing.service;

import com.ticketing.common.BusinessException;
import com.ticketing.mapper.TicketSkuMapper;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** SKU权威库存的事务写入口；调用方先遵守统一用户及父级锁序。 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class TicketInventory {
    private final TicketSkuMapper tickets;

    public TicketInventory(TicketSkuMapper tickets) {
        this.tickets = tickets;
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
