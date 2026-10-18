package com.ticketing.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ticketing.common.api.BusinessException;
import com.ticketing.common.api.PageResponse;
import com.ticketing.order.api.OrderReservation;
import com.ticketing.order.dto.CreateOrderRequest;
import com.ticketing.order.dto.OrderView;
import com.ticketing.order.dto.PaymentView;
import com.ticketing.order.dto.PurchaseLimit;
import com.ticketing.order.entity.TicketOrder;
import com.ticketing.order.mapper.OrderMapper;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneOffset;

/** 订单对外应用入口；写操作的事务边界统一在此，业务协作均通过其他Bean加入事务。 */
@Service
public class OrderService {
    private final OrderMapper orders;
    private final OrderReservation reservation;
    private final OrderStateService states;

    public OrderService(
            OrderMapper orders, OrderReservation reservation, OrderStateService states) {
        this.orders = orders;
        this.reservation = reservation;
        this.states = states;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OrderView create(long userId, CreateOrderRequest request, String key) {
        long skuId;
        try {
            skuId = Long.parseLong(request.skuId());
        } catch (NumberFormatException exception) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_SKU_ID", "票档编号超出允许范围");
        }
        return view(reservation.create(userId, skuId, request.quantity(), key), userId);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OrderView cancel(long userId, long orderId) {
        states.cancel(userId, orderId);
        return view(orderId, userId);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PaymentView pay(long userId, long orderId) {
        return states.pay(userId, orderId);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean expire(long userId, long orderId) {
        return states.expire(userId, orderId);
    }

    @Transactional(readOnly = true)
    public PaymentView payment(long userId, long orderId) {
        return states.payment(userId, orderId);
    }

    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public PageResponse<OrderView> list(long userId, int page, int size) {
        var now = orders.databaseNow().toInstant(ZoneOffset.UTC);
        long count =
                orders.selectCount(
                        new LambdaQueryWrapper<TicketOrder>().eq(TicketOrder::getUserId, userId));
        var items =
                orders.listOwned(userId, size, (long) (page - 1) * size).stream()
                        .map(order -> OrderView.from(order, now))
                        .toList();
        return new PageResponse<>(items, count, page, size);
    }

    @Transactional(readOnly = true)
    public OrderView detail(long userId, long orderId) {
        return view(orderId, userId);
    }

    @Transactional(readOnly = true)
    public PurchaseLimit limits(long userId, long sessionId) {
        return reservation.limits(userId, sessionId);
    }

    private OrderView view(long orderId, long userId) {
        TicketOrder order = orders.owned(orderId, userId);
        if (order == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "订单不存在");
        }
        return OrderView.from(order, orders.databaseNow().toInstant(ZoneOffset.UTC));
    }
}
