package com.ticketing.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ticketing.auth.entity.UserAccount;
import com.ticketing.auth.mapper.UserAccountMapper;
import com.ticketing.common.api.BusinessException;
import com.ticketing.common.api.PageResponse;
import com.ticketing.event.entity.Activity;
import com.ticketing.event.entity.ActivitySession;
import com.ticketing.event.mapper.ActivityMapper;
import com.ticketing.event.mapper.ActivitySessionMapper;
import com.ticketing.order.dto.CreateOrderRequest;
import com.ticketing.order.dto.OrderView;
import com.ticketing.order.entity.TicketOrder;
import com.ticketing.order.mapper.OrderMapper;
import com.ticketing.ticket.entity.TicketSku;
import com.ticketing.ticket.mapper.TicketSkuMapper;
import com.ticketing.ticket.model.SaleStatus;
import com.ticketing.ticket.service.TicketService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {
    private static final String CREATE_OPERATION = "CREATE_ORDER";
    private final OrderMapper orders;
    private final UserAccountMapper users;
    private final TicketSkuMapper tickets;
    private final ActivitySessionMapper sessions;
    private final ActivityMapper activities;
    private final int sessionLimit;
    private final int paymentMinutes;

    public OrderService(OrderMapper orders, UserAccountMapper users, TicketSkuMapper tickets,
            ActivitySessionMapper sessions, ActivityMapper activities,
            @Value("${ticketing.order.max-per-user-session:4}") int sessionLimit,
            @Value("${ticketing.order.payment-window-minutes:15}") int paymentMinutes) {
        if (sessionLimit < 1 || paymentMinutes < 1) throw new IllegalArgumentException("限购数量和支付窗口必须为正数");
        this.orders = orders;
        this.users = users;
        this.tickets = tickets;
        this.sessions = sessions;
        this.activities = activities;
        this.sessionLimit = sessionLimit;
        this.paymentMinutes = paymentMinutes;
    }

    /** 所有创建/取消先锁用户，再按活动、场次、票档、订单顺序加锁。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OrderView create(long userId, CreateOrderRequest request, String key) {
        long skuId = skuId(request.skuId());
        lockUser(userId);
        byte[] hash = requestHash(skuId, request.quantity());
        TicketOrder existing = orders.selectOne(new LambdaQueryWrapper<TicketOrder>()
                .eq(TicketOrder::getUserId, userId).eq(TicketOrder::getIdempotencyOperation, CREATE_OPERATION)
                .eq(TicketOrder::getIdempotencyKey, key));
        if (existing != null) {
            if (!MessageDigest.isEqual(existing.getRequestHash(), hash)) throw conflict("IDEMPOTENCY_CONFLICT", "同一幂等键不能用于不同票档或数量");
            return view(existing.getId(), userId);
        }
        SaleContext sale = lockSale(skuId);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        SaleStatus status = TicketService.saleStatus(sale.activity(), sale.session(), sale.sku(), now);
        if (status != SaleStatus.SELLING) {
            String message = switch (status) {
                case NOT_STARTED -> "票档暂未开售";
                case STOPPED -> "票档已停售";
                case SOLD_OUT -> "票档库存不足";
                default -> "活动、场次或票档暂不可售";
            };
            throw conflict("TICKET_UNAVAILABLE", message);
        }
        int quantity = request.quantity();
        if (quantity > sale.sku().getMaxQuantityPerOrder()) throw conflict("PURCHASE_LIMIT", "购买数量超过该票档单笔限购");
        long occupied = orders.occupied(userId, sale.session().getId());
        if (occupied + quantity > sessionLimit) throw conflict("PURCHASE_LIMIT", "同一用户每场最多占有" + sessionLimit + "张，请先取消不需要的待支付订单");
        long total;
        try { total = Math.multiplyExact(sale.sku().getPriceCents(), (long) quantity); }
        catch (ArithmeticException exception) { throw new BusinessException(HttpStatus.BAD_REQUEST, "ORDER_AMOUNT_INVALID", "订单金额超出允许范围"); }
        if (orders.reserve(skuId, quantity) != 1) throw conflict("STOCK_UNAVAILABLE", "库存不足或销售窗口已结束，请刷新票档");
        TicketOrder order = new TicketOrder();
        order.setOrderNo(UUID.randomUUID().toString().replace("-", ""));
        order.setUserId(userId);
        order.setSessionId(sale.session().getId());
        order.setStatus("PENDING");
        order.setTotalAmountCents(total);
        order.setIdempotencyOperation(CREATE_OPERATION);
        order.setIdempotencyKey(key);
        order.setRequestHash(hash);
        LocalDateTime created = LocalDateTime.now(ZoneOffset.UTC);
        order.setCreatedAt(created);
        order.setUpdatedAt(created);
        order.setExpiresAt(created.plusMinutes(paymentMinutes));
        // 不捕获唯一键异常后继续提交；任何落库失败都由事务整体回滚。
        orders.insert(order);
        orders.insertItem(order, sale.sku(), quantity);
        return view(order.getId(), userId);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OrderView cancel(long userId, long orderId) {
        lockUser(userId);
        TicketOrder initial = requireOwned(orderId, userId);
        SaleContext sale = lockSale(initial.getSkuId());
        TicketOrder order = orders.selectOne(new LambdaQueryWrapper<TicketOrder>()
                .eq(TicketOrder::getId, orderId).eq(TicketOrder::getUserId, userId).last("FOR UPDATE"));
        if (order == null) throw notFound();
        if ("CANCELLED".equals(order.getStatus())) return view(orderId, userId);
        if (!"PENDING".equals(order.getStatus())) throw conflict("ORDER_STATE_CONFLICT", "只有待支付订单可以主动取消");
        if (orders.release(sale.sku().getId(), initial.getQuantity()) != 1) throw conflict("STOCK_CONFLICT", "订单锁定库存不一致，取消未生效");
        int changed = orders.update(null, new LambdaUpdateWrapper<TicketOrder>().eq(TicketOrder::getId, orderId)
                .eq(TicketOrder::getUserId, userId).eq(TicketOrder::getStatus, "PENDING")
                .set(TicketOrder::getStatus, "CANCELLED").set(TicketOrder::getUpdatedAt, LocalDateTime.now(ZoneOffset.UTC)));
        if (changed != 1) throw conflict("ORDER_STATE_CONFLICT", "订单状态已变化，请刷新后重试");
        return view(orderId, userId);
    }

    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public PageResponse<OrderView> list(long userId, int page, int size) {
        Instant now = Instant.now();
        long count = orders.selectCount(new LambdaQueryWrapper<TicketOrder>().eq(TicketOrder::getUserId, userId));
        var items = orders.listOwned(userId, size, (long) (page - 1) * size).stream().map(order -> OrderView.from(order, now)).toList();
        return new PageResponse<>(items, count, page, size);
    }

    @Transactional(readOnly = true)
    public OrderView detail(long userId, long orderId) { return view(orderId, userId); }

    public PurchaseLimit limits(long userId, long sessionId) {
        if (sessions.selectById(sessionId) == null) throw new BusinessException(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND", "场次不存在");
        long used = orders.occupied(userId, sessionId);
        return new PurchaseLimit(sessionLimit, used, Math.max(0, sessionLimit - used));
    }

    private void lockUser(long id) {
        UserAccount account = users.selectOne(new LambdaQueryWrapper<UserAccount>()
                .select(UserAccount::getId, UserAccount::getStatus).eq(UserAccount::getId, id).last("FOR UPDATE"));
        if (account == null || !"ACTIVE".equals(account.getStatus())) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "ACCOUNT_UNAVAILABLE", "账号已停用或不存在，请重新登录");
        }
    }

    private SaleContext lockSale(long skuId) {
        TicketSku initial = tickets.selectById(skuId);
        if (initial == null) throw new BusinessException(HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND", "票档不存在");
        ActivitySession parent = sessions.selectById(initial.getSessionId());
        if (parent == null) throw new BusinessException(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND", "场次不存在");
        Activity activity = activities.selectOne(new LambdaQueryWrapper<Activity>().eq(Activity::getId, parent.getActivityId()).last("FOR SHARE"));
        ActivitySession session = sessions.selectOne(new LambdaQueryWrapper<ActivitySession>().eq(ActivitySession::getId, parent.getId()).last("FOR SHARE"));
        TicketSku sku = tickets.selectOne(new LambdaQueryWrapper<TicketSku>().eq(TicketSku::getId, skuId).last("FOR UPDATE"));
        if (activity == null || session == null || sku == null) throw conflict("TICKET_UNAVAILABLE", "票档所属活动或场次不存在");
        return new SaleContext(activity, session, sku);
    }

    private TicketOrder requireOwned(long id, long userId) {
        TicketOrder order = orders.owned(id, userId);
        if (order == null) throw notFound();
        return order;
    }
    private OrderView view(long id, long userId) { return OrderView.from(requireOwned(id, userId), Instant.now()); }
    private long skuId(String value) {
        try { return Long.parseLong(value); }
        catch (NumberFormatException exception) { throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_SKU_ID", "票档编号超出允许范围"); }
    }
    private byte[] requestHash(long skuId, int quantity) {
        try { return MessageDigest.getInstance("SHA-256").digest((skuId + ":" + quantity).getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("运行环境缺少SHA-256", exception); }
    }
    private BusinessException notFound() { return new BusinessException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "订单不存在"); }
    private BusinessException conflict(String code, String message) { return new BusinessException(HttpStatus.CONFLICT, code, message); }
    public record PurchaseLimit(int maxPerSession, long usedQuantity, long remainingQuantity) {}
    private record SaleContext(Activity activity, ActivitySession session, TicketSku sku) {}
}
