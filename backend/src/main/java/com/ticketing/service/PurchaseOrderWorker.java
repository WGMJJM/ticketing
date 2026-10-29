package com.ticketing.service;

import com.ticketing.dto.TradeContext;
import com.ticketing.entity.PurchaseRequest;
import com.ticketing.exception.BusinessException;
import com.ticketing.exception.BucketReservationRetry;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.concurrent.ThreadLocalRandom;
import com.ticketing.mapper.PurchaseRequestMapper;
import java.security.MessageDigest;
import java.util.Set;
import java.util.List;
import com.ticketing.enums.OrderOperation;
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
    private final List<PurchaseLifecycle> participants;

    public PurchaseOrderWorker(PurchaseRequestMapper requests, TradeResourceLocker resources, OrderReservation reservation,
            RedisStockReservationService stocks, PlatformTransactionManager manager, List<PurchaseLifecycle> participants) {
        this.requests = requests; this.resources = resources; this.reservation = reservation; this.stocks = stocks;
        this.participants = participants;
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
        // 正式 MQ/恢复入口在事务外调用。兼容已有事务调用者时由外层负责整笔回滚，
        // 绝不在仍持有外层锁的同一事务中回绕选桶或重试。
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            return executeAttempt(request, expiryOnly, false);
        }
        boolean orderedBuckets = false;
        int attempts = 0;
        while (true) {
            try { return executeAttempt(request, expiryOnly, orderedBuckets); }
            catch (BucketReservationRetry fallback) {
                if (orderedBuckets) throw fallback;
                orderedBuckets = true; // 前一完整事务已经回滚；回退不消耗瞬时故障重试预算。
            } catch (PessimisticLockingFailureException transientFailure) {
                if (++attempts >= 3) throw transientFailure;
                try {
                    Thread.sleep(ThreadLocalRandom.current().nextLong(
                            attempts == 1 ? 5 : 20, attempts == 1 ? 16 : 51));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("购票事务重试中断", interrupted);
                }
            }
        }
    }

    private PurchaseRequest executeAttempt(PurchaseRequest request, boolean expiryOnly, boolean orderedBuckets) {
        try {
            return transaction.execute(state -> {
            PurchaseRequest current = coordinate(request);
            if (current.terminal()) return current;
            if (!current.deadlineAt().isAfter(requests.now())) return fail(current, "EXPIRED", "QUEUE_EXPIRED");
            if (expiryOnly) { state.setRollbackOnly(); return null; }
            boolean ordinaryPurchase = OrderOperation.CREATE_ORDER.name().equals(current.idempotencyOperation())
                    && current.waitlistEntryId() == null;
            TradeContext context = ordinaryPurchase
                    ? resources.lockNewPurchase(current.userId(), current.skuId())
                    : resources.lockReservation(current.userId(), current.skuId(), true);
            participants.forEach(p -> p.validate(current));
            long orderId = ordinaryPurchase
                    ? reservation.reserveCoordinatedPurchase(context, current, orderedBuckets)
                    : reservation.reservePurchase(context, current.quantity(),
                            OrderOperation.valueOf(current.idempotencyOperation()), current.idempotencyKey(), current.id(), orderedBuckets);
            PurchaseRequest result = finishSuccess(current, orderId);
            participants.forEach(p -> p.allocated(result));
            return result;
            });
        } catch (BusinessException exception) {
            if (!BUSINESS_FAILURES.contains(exception.getCode()) && !"QUEUE_EXPIRED".equals(exception.getCode())) throw exception;
            // 整个建单事务已回滚，额度和库存没有部分写入；重新锁同一事实登记确定失败。
            // 若重复消费者或过期仲裁已经先完成，直接采用其已提交终态。
            return transaction.execute(state -> {
                PurchaseRequest current = coordinate(request);
                if (current.terminal()) return current;
                boolean expired = "QUEUE_EXPIRED".equals(exception.getCode()) || !current.deadlineAt().isAfter(requests.now());
                return fail(current, expired ? "EXPIRED" : "FAILED", expired ? "QUEUE_EXPIRED" : exception.getCode());
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
                || !current.idempotencyOperation().equals(request.idempotencyOperation())
                || !java.util.Objects.equals(current.waitlistEntryId(), request.waitlistEntryId())
                || !MessageDigest.isEqual(current.requestHash(), request.requestHash())) {
            throw new IllegalStateException("购票请求唯一约束或凭据关联不一致");
        }
        return current;
    }

    private PurchaseRequest fail(PurchaseRequest request, String status, String code) {
        PurchaseRequest result = finish(request, status, null, code);
        participants.forEach(p -> p.failed(result));
        return result;
    }

    private PurchaseRequest finish(PurchaseRequest request, String status, Long orderId, String failure) {
        if (requests.finish(request.id(), status, orderId, failure) != 1) throw new IllegalStateException("购票终态未能保存");
        return requests.find(request.id());
    }

    private PurchaseRequest finishSuccess(PurchaseRequest request, long orderId) {
        if (requests.finishSuccess(request.id(), orderId) != 1) {
            if (!request.deadlineAt().isAfter(requests.now())) {
                throw new BusinessException(org.springframework.http.HttpStatus.CONFLICT, "QUEUE_EXPIRED", "购票申请已过期");
            }
            throw new IllegalStateException("购票终态未能保存");
        }
        // 仅用于当前事务的业务联动和提交后结算，不冒充数据库生成的 updatedAt。
        // 需要完整数据库时间的详情查询仍从 Mapper 读取。
        return new PurchaseRequest(request.id(), request.userId(), request.skuId(), request.quantity(),
                request.idempotencyKey(), request.requestHash(), "SUCCEEDED", orderId, null,
                request.acceptedAt(), request.deadlineAt(), null,
                request.idempotencyOperation(), request.waitlistEntryId());
    }
}
