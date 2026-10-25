package com.ticketing.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ticketing.config.PurchaseProperties;
import com.ticketing.entity.PurchaseRequest;
import com.ticketing.entity.UserAccount;
import com.ticketing.enums.InventoryChangeReason;
import com.ticketing.exception.BusinessException;
import com.ticketing.mapper.PurchaseRequestMapper;
import com.ticketing.mapper.UserAccountMapper;
import com.ticketing.messaging.DeliveryObservation;
import com.ticketing.messaging.InventoryEvents;
import com.ticketing.messaging.PurchaseCommands;
import com.ticketing.dto.StockReservation;
import com.ticketing.vo.PurchaseRequestView;
import io.micrometer.core.instrument.MeterRegistry;
import java.security.MessageDigest;
import java.time.ZoneOffset;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Redis预扣后的MySQL受理边界，申请与既有Outbox在同一短事务提交。 */
@Service
public class PurchaseAdmissionTransaction {
    private final PurchaseRequestMapper requests;
    private final UserAccountMapper users;
    private final PurchaseCommands commands;
    private final PurchaseProperties properties;
    private final InventoryEvents inventoryEvents;
    private final MeterRegistry meters;

    public PurchaseAdmissionTransaction(PurchaseRequestMapper requests, UserAccountMapper users,
            PurchaseCommands commands, PurchaseProperties properties, InventoryEvents inventoryEvents,
            MeterRegistry meters) {
        this.requests = requests;
        this.users = users;
        this.commands = commands;
        this.properties = properties;
        this.inventoryEvents = inventoryEvents;
        this.meters = meters;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 30)
    public PurchaseRequestView accept(long userId, long skuId, int quantity, String key, byte[] hash,
            byte[] token) {
        long started = System.nanoTime();
        UserAccount user;
        try {
            user = users.selectOne(new LambdaQueryWrapper<UserAccount>()
                    .select(UserAccount::getId, UserAccount::getStatus)
                    .eq(UserAccount::getId, userId).last("FOR UPDATE"));
        } finally {
            meters.timer("ticketing.purchase.admission.lock.duration", "resource", "account")
                    .record(System.nanoTime() - started, java.util.concurrent.TimeUnit.NANOSECONDS);
        }
        if (user == null || !"ACTIVE".equals(user.getStatus())) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "ACCOUNT_UNAVAILABLE", "账号已停用或不存在，请重新登录");
        }
        PurchaseRequest existing = requests.byKey(userId, key);
        if (existing != null) {
            requireSame(existing, hash, token);
            return view(existing);
        }
        if (requests.insert(userId, skuId, quantity, key, hash, token,
                properties.queueSeconds(), properties.recoverySeconds()) != 1) {
            throw new IllegalStateException("购票申请未能写入");
        }
        long id = requests.insertedId();
        commands.append(id);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                DeliveryObservation.record("purchase-" + id + ":1", "accepted-commit");
            }
        });
        return view(requests.find(id));
    }

    /**
     * 用户行锁将迟到的HTTP受理与恢复串行化。没有数据库申请时写终态幂等事实，
     * 再由调用方释放Redis token，杜绝先释放库存、后出现新申请的竞态。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 30)
    public OrphanResolution recoverOrphan(StockReservation reservation) {
        UserAccount user = users.selectOne(new LambdaQueryWrapper<UserAccount>()
                .select(UserAccount::getId, UserAccount::getStatus)
                .eq(UserAccount::getId, reservation.userId()).last("FOR UPDATE"));
        PurchaseRequest existing = requests.byKey(reservation.userId(), reservation.idempotencyKey());
        if (existing != null) {
            if (!MessageDigest.isEqual(existing.requestHash(), reservation.requestHash())) {
                appendRelease(reservation);
                return new OrphanResolution(true, existing);
            }
            if (!MessageDigest.isEqual(existing.stockReservationToken(), reservation.token())) {
                throw new IllegalStateException("同一购票幂等事实绑定了不同Redis预扣token");
            }
            return new OrphanResolution(false, existing);
        }

        String failure = user == null || !"ACTIVE".equals(user.getStatus())
                ? "ACCOUNT_UNAVAILABLE" : "STOCK_RESERVATION_EXPIRED";
        if (user != null) {
            if (requests.insertTerminal(reservation.userId(), reservation.skuId(), reservation.quantity(),
                    reservation.idempotencyKey(), reservation.requestHash(), reservation.token(), failure) != 1) {
                throw new IllegalStateException("购票预扣恢复终态未能写入");
            }
        }
        appendRelease(reservation);
        return new OrphanResolution(true, user == null ? null : requests.byKey(reservation.userId(), reservation.idempotencyKey()));
    }

    private void appendRelease(StockReservation reservation) {
        inventoryEvents.appendOnce(reservation.skuId(), InventoryChangeReason.PURCHASE_RESERVATION_RELEASED,
                RedisStockReservationService.releaseEventId(reservation.token()));
    }

    private PurchaseRequestView view(PurchaseRequest request) {
        return PurchaseRequestView.from(request, requests.now().toInstant(ZoneOffset.UTC));
    }

    private void requireSame(PurchaseRequest existing, byte[] hash, byte[] token) {
        if (!MessageDigest.isEqual(existing.requestHash(), hash)) {
            throw new BusinessException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "同一幂等键不能用于不同票档或数量");
        }
        if (!MessageDigest.isEqual(existing.stockReservationToken(), token)) {
            throw new IllegalStateException("购票申请幂等token与请求参数不一致");
        }
    }

    public record OrphanResolution(boolean release, PurchaseRequest request) { }
}
