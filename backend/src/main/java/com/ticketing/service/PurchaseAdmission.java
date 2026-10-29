package com.ticketing.service;

import com.ticketing.entity.PurchaseRequest;
import com.ticketing.dto.PurchaseIntent;
import com.ticketing.exception.BusinessException;
import com.ticketing.mapper.PurchaseRequestMapper;
import com.ticketing.messaging.PurchasePublisher;
import com.ticketing.vo.PurchaseRequestView;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PurchaseAdmission {
    private final RedisStockReservationService stocks;
    private final PurchasePublisher publisher;
    private final PurchaseRequestMapper requests;

    public PurchaseAdmission(RedisStockReservationService stocks, PurchasePublisher publisher, PurchaseRequestMapper requests) {
        this.stocks = stocks; this.publisher = publisher; this.requests = requests;
    }

    /** 普通 HTTP 主链路没有任何购票申请 SQL；认证和原安全限流仍由过滤器完成。 */
    @Transactional(propagation = Propagation.NEVER)
    public PurchaseRequestView accept(long userId, String sku, int quantity, String key) {
        long skuId;
        try { skuId = Long.parseLong(sku); if (skuId <= 0) throw new NumberFormatException(); }
        catch (NumberFormatException exception) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_SKU_ID", "票档编号超出允许范围");
        }
        return view(submit(new PurchaseIntent(userId, skuId, quantity, "CREATE_ORDER", key, null, 0, null, null)));
    }

    @Transactional(propagation = Propagation.NEVER)
    public PurchaseRequest submit(PurchaseIntent intent) {
        PurchaseRequest request = stocks.reserve(intent);
        if (!request.terminal()) publisher.publish(request.id(), request.skuId());
        return request;
    }

    /** 内部调度仅查询已绑定的申请，不产生新的预扣。 */
    public PurchaseRequest result(long id) {
        PurchaseRequest committed = requests.find(id);
        return committed == null ? stocks.find(id) : committed;
    }

    public PurchaseRequestView detail(long userId, long id) {
        PurchaseRequest request = requests.find(id);
        if (request == null) request = stocks.find(id);
        if (request == null || request.userId() != userId) throw notFound();
        return view(request);
    }

    public PurchaseRequestView lookup(long userId, String key) {
        PurchaseRequest request = requests.byKey(userId, key);
        if (request == null) request = stocks.lookup(userId, key);
        if (request == null) throw notFound();
        return view(request);
    }

    public List<PurchaseRequestView> active(long userId) {
        return stocks.active(userId).stream().map(p -> {
            PurchaseRequest committed = requests.find(p.id());
            return committed == null ? p : committed;
        }).filter(p -> !p.terminal()).map(this::view).toList();
    }

    private PurchaseRequestView view(PurchaseRequest request) { return PurchaseRequestView.from(request, Instant.now()); }
    private BusinessException notFound() {
        return new BusinessException(HttpStatus.NOT_FOUND, "PURCHASE_REQUEST_NOT_FOUND", "购票请求不存在");
    }
}
