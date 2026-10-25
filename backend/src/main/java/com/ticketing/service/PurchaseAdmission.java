package com.ticketing.service;

import com.ticketing.entity.PurchaseRequest;
import com.ticketing.dto.StockReservation;
import com.ticketing.exception.BusinessException;
import com.ticketing.mapper.PurchaseRequestMapper;
import com.ticketing.vo.PurchaseRequestView;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Redis预扣后写入持久申请；原查询、202与用户幂等协议保持不变。 */
@Service
public class PurchaseAdmission {
    private final PurchaseRequestMapper requests;
    private final TicketInventory inventory;
    private final RedisStockReservationService stocks;
    private final PurchaseAdmissionTransaction transaction;

    public PurchaseAdmission(PurchaseRequestMapper requests, TicketInventory inventory,
            RedisStockReservationService stocks, PurchaseAdmissionTransaction transaction) {
        this.requests = requests;
        this.inventory = inventory;
        this.stocks = stocks;
        this.transaction = transaction;
    }

    @Transactional(propagation = Propagation.NEVER)
    public PurchaseRequestView accept(long userId, String sku, int quantity, String key) {
        long skuId = skuId(sku);
        PurchaseRequestView existing = replay(userId, skuId, quantity, key);
        if (existing != null) return existing;

        int version = inventory.requirePurchaseEligible(skuId);
        byte[] hash = hash(skuId, quantity);
        byte[] token = reservationToken(userId, key, hash);
        StockReservation reservation = new StockReservation(token, userId, skuId, quantity, key, hash);
        stocks.reserve(reservation, version);

        PurchaseRequestView accepted = transaction.accept(userId, skuId, quantity, key, hash, token);
        long requestId = Long.parseLong(accepted.requestId());
        if (!stocks.bind(token, requestId)) stocks.synchronize(skuId);
        if (accepted.terminal()) stocks.settle(token, accepted.status());
        return accepted;
    }

    @Transactional(readOnly = true)
    public PurchaseRequestView detail(long userId, long id) {
        PurchaseRequest request = requests.find(id);
        if (request == null || request.userId() != userId) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "PURCHASE_REQUEST_NOT_FOUND", "购票请求不存在");
        }
        return view(request);
    }

    @Transactional(readOnly = true)
    public List<PurchaseRequestView> active(long userId) {
        return requests.active(userId).stream().map(this::view).toList();
    }

    /** 只恢复原申请事实，不创建新请求，也不绕过POST上的安全限流。 */
    @Transactional(readOnly = true)
    public PurchaseRequestView lookup(long userId, String key) {
        PurchaseRequest existing = requests.byKey(userId, key);
        if (existing == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "PURCHASE_REQUEST_NOT_FOUND", "购票请求不存在");
        }
        return view(existing);
    }

    /** 安全过滤器已校验可信用户；幂等查重在库存预扣和POST安全限流之前执行。 */
    @Transactional(readOnly = true)
    public PurchaseRequestView replay(long userId, long skuId, int quantity, String key) {
        PurchaseRequest existing = requests.byKey(userId, key);
        if (existing == null) return null;
        byte[] hash = hash(skuId, quantity);
        requireSame(existing, hash, reservationToken(userId, key, hash));
        return view(existing);
    }

    public long skuId(String sku) {
        try {
            long id = Long.parseLong(sku);
            if (id <= 0) throw new NumberFormatException();
            return id;
        } catch (NumberFormatException exception) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_SKU_ID", "票档编号超出允许范围");
        }
    }

    private void requireSame(PurchaseRequest existing, byte[] hash, byte[] token) {
        if (!MessageDigest.isEqual(existing.requestHash(), hash)) {
            throw new BusinessException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "同一幂等键不能用于不同票档或数量");
        }
        if (!MessageDigest.isEqual(existing.stockReservationToken(), token)) {
            throw new IllegalStateException("购票申请幂等token与请求参数不一致");
        }
    }

    private PurchaseRequestView view(PurchaseRequest request) {
        return PurchaseRequestView.from(request, requests.now().toInstant(ZoneOffset.UTC));
    }

    private byte[] hash(long sku, int quantity) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest((sku + ":" + quantity).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境缺少SHA-256", exception);
        }
    }

    private byte[] reservationToken(long userId, String key, byte[] requestHash) {
        String stable = userId + "\nCREATE_ORDER\n" + key + "\n" + HexFormat.of().formatHex(requestHash);
        try {
            return MessageDigest.getInstance("SHA-256").digest(stable.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境缺少SHA-256", exception);
        }
    }
}
