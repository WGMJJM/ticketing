package com.ticketing.service;

import com.ticketing.config.OrderProperties;
import com.ticketing.dto.TicketSale;
import com.ticketing.dto.TradeContext;
import com.ticketing.entity.TicketOrder;
import com.ticketing.enums.OrderOperation;
import com.ticketing.enums.OrderStatus;
import com.ticketing.enums.SaleStatus;
import com.ticketing.exception.BusinessException;
import com.ticketing.mapper.ActivitySessionMapper;
import com.ticketing.mapper.OrderMapper;
import com.ticketing.vo.PurchaseLimit;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.UUID;

/** 普通购票和候补分配共用预占、限额、金额与幂等。返回订单ID，不暴露订单可写实体。 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class OrderReservation {
    private final OrderMapper orders;
    private final TicketInventory inventory;
    private final ActivitySessionMapper sessions;
    private final OrderProperties properties;
    private final UserSessionQuotaService quotas;

    public OrderReservation(
            OrderMapper orders,
            TicketInventory inventory,
            ActivitySessionMapper sessions,
            OrderProperties properties, UserSessionQuotaService quotas) {
        this.orders = orders;
        this.inventory = inventory;
        this.sessions = sessions;
        this.properties = properties;
        this.quotas = quotas;
    }

    /** 消费者已按用户→父级→SKU加锁，复用唯一的预占和金额快照算法。 */
    public long reservePurchase(TradeContext context, int quantity, String key) {
        return reservePurchase(context, quantity, OrderOperation.CREATE_ORDER, key);
    }

    public long reservePurchase(TradeContext context, int quantity, OrderOperation operation, String key) {
        byte[] hash = requestHash(context.sale().skuId(), quantity);
        TicketOrder existing = find(context.userId(), operation, key);
        if (existing != null) {
            requireHash(existing, hash);
            return existing.getId();
        }
        return insert(context.userId(), quantity, operation, key, hash, context.sale());
    }

    public boolean eligibleForWaitlist(TradeContext context, int quantity, LocalDateTime now) {
        TicketSale sale = context.sale();
        SaleStatus status = sale.statusAt(now);
        if (!context.active() || sale.bucketed()
                || (status != SaleStatus.SELLING && status != SaleStatus.SOLD_OUT)
                || quantity < 1
                || quantity > sale.maxQuantityPerOrder()
                || quantity > remaining(context.userId(), sale.sessionId())) {
            return false;
        }
        try {
            Math.multiplyExact(sale.priceCents(), (long) quantity);
            return true;
        } catch (ArithmeticException exception) {
            return false;
        }
    }

    @Transactional(readOnly = true)
    public PurchaseLimit limits(long userId, long sessionId) {
        if (sessions.selectById(sessionId) == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND", "场次不存在");
        }
        long used = quotas.occupied(userId, sessionId);
        return new PurchaseLimit(
                properties.maxPerUserSession(),
                used,
                Math.max(0, properties.maxPerUserSession() - used));
    }

    private long remaining(long userId, long sessionId) {
        return Math.max(0, properties.maxPerUserSession() - quotas.occupied(userId, sessionId));
    }

    private long insert(
            long userId,
            int quantity,
            OrderOperation operation,
            String key,
            byte[] hash,
            TicketSale sale) {
        SaleStatus status = sale.statusAt(orders.databaseNow());
        if (status != SaleStatus.SELLING) {
            String message =
                    switch (status) {
                        case NOT_STARTED -> "票档暂未开售";
                        case STOPPED -> "票档已停售";
                        case SOLD_OUT -> "票档库存不足";
                        default -> "活动、场次或票档暂不可售";
                    };
            throw conflict("TICKET_UNAVAILABLE", message);
        }
        if (quantity > sale.maxQuantityPerOrder()) {
            throw conflict("PURCHASE_LIMIT", "购买数量超过该票档单笔限购");
        }
        long total;
        try {
            total = Math.multiplyExact(sale.priceCents(), (long) quantity);
        } catch (ArithmeticException exception) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST, "ORDER_AMOUNT_INVALID", "订单金额超出允许范围");
        }
        quotas.occupy(userId, sale.sessionId(), quantity);
        inventory.reserve(sale, quantity);
        TicketOrder order = new TicketOrder();
        order.setOrderNo(UUID.randomUUID().toString().replace("-", ""));
        order.setUserId(userId);
        order.setSessionId(sale.sessionId());
        order.setStatus(OrderStatus.PENDING.name());
        order.setTotalAmountCents(total);
        order.setIdempotencyOperation(operation.name());
        order.setIdempotencyKey(key);
        order.setRequestHash(hash);
        orders.insertPurchase(order, properties.paymentWindowMinutes());
        orders.insertItem(order, sale, quantity);
        return order.getId();
    }

    private TicketOrder find(long userId, OrderOperation operation, String key) {
        return orders.selectOne(
                new LambdaQueryWrapper<TicketOrder>()
                        .eq(TicketOrder::getUserId, userId)
                        .eq(TicketOrder::getIdempotencyOperation, operation.name())
                        .eq(TicketOrder::getIdempotencyKey, key));
    }

    private void requireHash(TicketOrder existing, byte[] hash) {
        if (!MessageDigest.isEqual(existing.getRequestHash(), hash)) {
            throw conflict("IDEMPOTENCY_CONFLICT", "同一幂等键不能用于不同票档或数量");
        }
    }

    private byte[] requestHash(long skuId, int quantity) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest((skuId + ":" + quantity).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境缺少SHA-256", exception);
        }
    }

    private BusinessException conflict(String code, String message) {
        return new BusinessException(HttpStatus.CONFLICT, code, message);
    }
}
