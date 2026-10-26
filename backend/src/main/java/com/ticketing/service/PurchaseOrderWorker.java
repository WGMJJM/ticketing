package com.ticketing.service;

import com.ticketing.dto.TradeContext;
import com.ticketing.entity.PurchaseRequest;
import com.ticketing.exception.BusinessException;
import com.ticketing.mapper.PurchaseRequestMapper;
import java.security.MessageDigest;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 消费和过期仲裁共用 requestId 行锁，订单、库存、额度和最终结果只有一个本地事务。 */
@Service
public class PurchaseOrderWorker {
    private static final Set<String> BUSINESS_FAILURES = Set.of("TICKET_UNAVAILABLE", "STOCK_UNAVAILABLE",
            "TICKET_NOT_FOUND", "SESSION_NOT_FOUND", "PURCHASE_LIMIT", "ACCOUNT_UNAVAILABLE", "ORDER_AMOUNT_INVALID");
    private final PurchaseRequestMapper requests;
    private final TradeResourceLocker resources;
    private final OrderReservation reservation;
    private final RedisStockReservationService stocks;
    private final TransactionTemplate transaction;

    public PurchaseOrderWorker(PurchaseRequestMapper requests, TradeResourceLocker resources, OrderReservation reservation,
            RedisStockReservationService stocks, PlatformTransactionManager manager) {
        this.requests = requests; this.resources = resources; this.reservation = reservation; this.stocks = stocks;
        transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(30);
    }

    public void process(long id) {
        PurchaseRequest request = stocks.find(id);
        if (request == null) {
            PurchaseRequest known = requests.find(id);
            if (known != null && known.terminal()) { stocks.settle(known); return; }
            if (known != null) stocks.pause(known.skuId());
            throw new IllegalStateException("预扣凭据缺失，停止对应票档受理并等待核对");
        }
        process(id, request.skuId());
    }

    public void process(long id, long skuId) {
        PurchaseRequest known = requests.find(id);
        if (known != null && known.terminal()) {
            if (known.skuId() != skuId) throw new IllegalStateException("消息票档与已提交结果不一致");
            stocks.settle(known);
            return;
        }
        PurchaseRequest request = stocks.require(id, skuId);
        PurchaseRequest result = execute(request, false);
        stocks.settle(result); // SQL 已提交；Redis 故障由同一个恢复入口补齐，不返票猜测。
    }

    public boolean recover(long id, long skuId) {
        PurchaseRequest request = stocks.require(id, skuId);
        PurchaseRequest finalResult = requests.find(id);
        if (finalResult == null || !finalResult.terminal()) {
            finalResult = execute(request, true);
            if (finalResult == null) return false;
        }
        stocks.settle(finalResult);
        return true;
    }

    private PurchaseRequest execute(PurchaseRequest request, boolean expiryOnly) {
        try {
            return transaction.execute(state -> {
            PurchaseRequest current = coordinate(request);
            if (current.terminal()) return current;
            if (!current.deadlineAt().isAfter(requests.now())) return finish(current, "EXPIRED", null, "QUEUE_EXPIRED");
            if (expiryOnly) { state.setRollbackOnly(); return null; }
            TradeContext context = resources.lockReservation(current.userId(), current.skuId(), true);
            long orderId = reservation.reservePurchase(context, current.quantity(), current.idempotencyKey());
            if (!current.deadlineAt().isAfter(requests.now())) {
                throw new BusinessException(org.springframework.http.HttpStatus.CONFLICT, "QUEUE_EXPIRED", "购票申请已过期");
            }
            return finish(current, "SUCCEEDED", orderId, null);
            });
        } catch (BusinessException exception) {
            if (!BUSINESS_FAILURES.contains(exception.getCode()) && !"QUEUE_EXPIRED".equals(exception.getCode())) throw exception;
            // 整个建单事务已回滚，额度和库存没有部分写入；重新锁同一事实登记确定失败。
            // 若重复消费者或过期仲裁已经先完成，直接采用其已提交终态。
            return transaction.execute(state -> {
                PurchaseRequest current = coordinate(request);
                if (current.terminal()) return current;
                boolean expired = "QUEUE_EXPIRED".equals(exception.getCode()) || !current.deadlineAt().isAfter(requests.now());
                return finish(current, expired ? "EXPIRED" : "FAILED", null, expired ? "QUEUE_EXPIRED" : exception.getCode());
            });
        } catch (IllegalStateException inconsistency) {
            stocks.pause(request.skuId());
            throw inconsistency;
        }
    }

    private PurchaseRequest coordinate(PurchaseRequest request) {
        requests.coordinate(request);
        PurchaseRequest current = requests.lock(request.id());
        if (current == null || current.userId() != request.userId() || current.skuId() != request.skuId()
                || current.quantity() != request.quantity() || !current.idempotencyKey().equals(request.idempotencyKey())
                || !MessageDigest.isEqual(current.requestHash(), request.requestHash())) {
            throw new IllegalStateException("购票请求唯一约束或凭据关联不一致");
        }
        return current;
    }

    private PurchaseRequest finish(PurchaseRequest request, String status, Long orderId, String failure) {
        if (requests.finish(request.id(), status, orderId, failure) != 1) throw new IllegalStateException("购票终态未能保存");
        return requests.find(request.id());
    }
}
