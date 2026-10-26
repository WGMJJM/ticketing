package com.ticketing.service;

import com.ticketing.cache.CatalogCache;
import com.ticketing.dto.TicketSale;
import com.ticketing.dto.TicketWriteRequest;
import com.ticketing.dto.VersionRequest;
import com.ticketing.entity.Activity;
import com.ticketing.entity.ActivitySession;
import com.ticketing.entity.TicketSku;
import com.ticketing.enums.AuditAction;
import com.ticketing.enums.InventoryChangeReason;
import com.ticketing.enums.SaleStatus;
import com.ticketing.enums.TicketStatus;
import com.ticketing.exception.BusinessException;
import com.ticketing.mapper.ActivityMapper;
import com.ticketing.mapper.ActivitySessionMapper;
import com.ticketing.mapper.TicketSkuMapper;
import com.ticketing.messaging.InventoryEvents;
import com.ticketing.vo.TicketView;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import java.util.Map;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Service
public class TicketService implements SessionWindowGuard {
    private static final int MAX_TICKETS_PER_SESSION = 200;
    private final TicketSkuMapper tickets;
    private final ActivitySessionMapper sessions;
    private final ActivityMapper activities;
    private final InventoryEvents events;
    private final AuditWriter audit;
    private final CatalogCache cache;
    private final TicketInventory inventory;

    public TicketService(
            TicketSkuMapper tickets,
            ActivitySessionMapper sessions,
            ActivityMapper activities,
            InventoryEvents events, AuditWriter audit, CatalogCache cache, TicketInventory inventory) {
        this.tickets = tickets;
        this.sessions = sessions;
        this.activities = activities;
        this.events = events;
        this.audit = audit;
        this.cache = cache;
        this.inventory = inventory;
    }

    @Transactional(readOnly = true)
    public List<TicketView> publicList(long activityId, long sessionId) {
        Activity activity = activities.selectById(activityId);
        if (activity == null || !"PUBLISHED".equals(activity.getStatus())) {
            throw notFound("ACTIVITY_NOT_FOUND", "活动不存在或暂未开放");
        }
        ActivitySession session = requireSession(sessionId, false);
        if (session.getActivityId() != activityId) {
            throw notFound("SESSION_NOT_FOUND", "该活动下不存在此场次");
        }
        return list(session, activity, true);
    }

    @Transactional(readOnly = true)
    public List<TicketView> adminList(long sessionId) {
        ActivitySession session = requireSession(sessionId, false);
        return list(session, activities.selectById(session.getActivityId()), false);
    }

    private List<TicketView> list(ActivitySession session, Activity activity, boolean publicOnly) {
        var query =
                new LambdaQueryWrapper<TicketSku>().eq(TicketSku::getSessionId, session.getId());
        if (publicOnly) {
            query.eq(TicketSku::getStatus, TicketStatus.ON_SALE.name());
        }
        query.orderByAsc(TicketSku::getPriceCents)
                .orderByAsc(TicketSku::getId)
                .last("LIMIT " + MAX_TICKETS_PER_SESSION);
        Instant now = Instant.now();
        LocalDateTime utcNow = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
        return tickets.selectList(query).stream()
                .map(inventory::balance)
                .map(sku -> TicketView.from(sku, saleStatus(activity, session, sku, utcNow), now))
                .toList();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TicketView create(long sessionId, TicketWriteRequest request) {
        long operatorId = audit.begin();
        ActivitySession session = requireSession(sessionId, true);
        validateSession(session);
        validateWindow(session, request);
        if (tickets.selectCount(
                        new LambdaQueryWrapper<TicketSku>().eq(TicketSku::getSessionId, sessionId))
                >= MAX_TICKETS_PER_SESSION) {
            throw conflict("每个场次最多支持200个票档");
        }
        requireUniqueName(sessionId, request.name().trim(), null);
        TicketSku sku = new TicketSku();
        sku.setSessionId(sessionId);
        sku.setName(request.name().trim());
        sku.setPriceCents(price(request.priceCents()));
        sku.setTotalStock(request.totalStock());
        sku.setLockedStock(0);
        sku.setSoldStock(0);
        sku.setInventoryStrategy("SINGLE");
        sku.setBucketCount(0);
        sku.setSaleStartsAt(utc(request.saleStartsAt()));
        sku.setSaleEndsAt(utc(request.saleEndsAt()));
        sku.setMaxQuantityPerOrder(request.maxQuantityPerOrder());
        sku.setStatus(TicketStatus.DRAFT.name());
        sku.setVersion(0);
        sku.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        sku.setUpdatedAt(sku.getCreatedAt());
        try {
            tickets.insert(sku);
        } catch (DuplicateKeyException exception) {
            throw duplicateName();
        }
        audit.record(operatorId, AuditAction.TICKET_CREATE, "TICKET_SKU", sku.getId(), Map.of(), snapshot(sku));
        cache.invalidateAfterCommit();
        return view(sku, session);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TicketView update(long id, TicketWriteRequest request) {
        long operatorId = audit.begin();
        TicketSku existing = requireTicket(id);
        ActivitySession session = requireSession(existing.getSessionId(), true);
        TicketSku sku = inventory.balance(requireTicket(id));
        requireVersion(sku, request.version());
        validateSession(session);
        validateWindow(session, request);
        long price = price(request.priceCents());
        if (TicketStatus.ON_SALE.name().equals(sku.getStatus())
                && sensitiveFieldsChanged(sku, request, price)) {
            throw conflict("已启用销售的票档只能调整总库存，修改其他信息请先下架");
        }
        if ((long) sku.getLockedStock() + sku.getSoldStock() > request.totalStock()) {
            throw conflict("总库存不能小于已锁定库存与已售库存之和");
        }
        requireUniqueName(session.getId(), request.name().trim(), id);
        inventory.resize(sku, request.totalStock());
        var update =
                mutation(sku)
                        .set(TicketSku::getName, request.name().trim())
                        .set(TicketSku::getPriceCents, price)
                        .set(TicketSku::getTotalStock, request.totalStock())
                        .set(TicketSku::getSaleStartsAt, utc(request.saleStartsAt()))
                        .set(TicketSku::getSaleEndsAt, utc(request.saleEndsAt()))
                        .set(TicketSku::getMaxQuantityPerOrder, request.maxQuantityPerOrder())
                        .apply(
                                "CAST(locked_stock AS SIGNED) + sold_stock <= {0}",
                                request.totalStock());
        try {
            if (tickets.update(null, update) != 1) {
                throw conflict("票档或库存已变化，请刷新后重试");
            }
        } catch (DuplicateKeyException exception) {
            throw duplicateName();
        }
        events.append(id, request.totalStock() > sku.getTotalStock()
                ? InventoryChangeReason.STOCK_INCREASED : InventoryChangeReason.SKU_CONFIGURATION_CHANGED);
        audit.record(operatorId, !sku.getTotalStock().equals(request.totalStock()) ? AuditAction.STOCK_ADJUST : AuditAction.TICKET_UPDATE,
                "TICKET_SKU", id, snapshot(sku), snapshot(requireTicket(id)));
        cache.invalidateAfterCommit();
        return view(requireTicket(id), session);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TicketView publish(long id, VersionRequest request) {
        long operatorId = audit.begin();
        TicketSku existing = requireTicket(id);
        ActivitySession session = requireSession(existing.getSessionId(), true);
        TicketSku sku = requireTicket(id);
        if (sku.bucketed()) throw conflict("历史分桶票档已停止新销售，仅保留既有订单履约和售后");
        requireVersion(sku, request.version());
        if (TicketStatus.ON_SALE.name().equals(sku.getStatus())) {
            throw conflict("票档已经启用销售");
        }
        validateSession(session);
        if (sku.getSaleStartsAt().isBefore(session.getSaleStartsAt())
                || sku.getSaleEndsAt().isAfter(session.getStartsAt())) {
            throw conflict("票档销售窗口与场次时间不一致，请先编辑票档");
        }
        if (!sku.getSaleEndsAt().isAfter(LocalDateTime.now(ZoneOffset.UTC))) {
            throw conflict("销售窗口已结束，不能启用销售");
        }
        if (tickets.update(
                        null, mutation(sku).set(TicketSku::getStatus, TicketStatus.ON_SALE.name()))
                != 1) {
            throw conflict("票档状态已变化，请刷新后重试");
        }
        events.append(id, InventoryChangeReason.SKU_CONFIGURATION_CHANGED);
        audit.record(operatorId, AuditAction.TICKET_PUBLISH, "TICKET_SKU", id, snapshot(sku), snapshot(requireTicket(id)));
        cache.invalidateAfterCommit();
        return view(requireTicket(id), session);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TicketView offline(long id, VersionRequest request) {
        long operatorId = audit.begin();
        TicketSku existing = requireTicket(id);
        ActivitySession session = requireSession(existing.getSessionId(), true);
        TicketSku sku = requireTicket(id);
        requireVersion(sku, request.version());
        if (!TicketStatus.ON_SALE.name().equals(sku.getStatus())) {
            throw conflict("只有已启用销售的票档可以下架");
        }
        if (tickets.update(
                        null, mutation(sku).set(TicketSku::getStatus, TicketStatus.OFF_SALE.name()))
                != 1) {
            throw conflict("票档状态已变化，请刷新后重试");
        }
        events.append(id, InventoryChangeReason.SKU_CONFIGURATION_CHANGED);
        audit.record(operatorId, AuditAction.TICKET_OFFLINE, "TICKET_SKU", id, snapshot(sku), snapshot(requireTicket(id)));
        cache.invalidateAfterCommit();
        return view(requireTicket(id), session);
    }

    public static SaleStatus saleStatus(
            Activity activity, ActivitySession session, TicketSku sku, LocalDateTime now) {
        return TicketSale.from(activity, session, sku).statusAt(now);
    }

    private Map<String, Object> snapshot(TicketSku sku) {
        return Map.of("name", sku.getName(), "status", sku.getStatus(), "priceCents", sku.getPriceCents().toString(),
                "totalStock", sku.getTotalStock(), "maxQuantityPerOrder", sku.getMaxQuantityPerOrder(),
                "saleStartsAt", sku.getSaleStartsAt().atOffset(ZoneOffset.UTC),
                "saleEndsAt", sku.getSaleEndsAt().atOffset(ZoneOffset.UTC), "version", sku.getVersion());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void validate(long sessionId, LocalDateTime saleStartsAt, LocalDateTime startsAt) {
        long inconsistent =
                tickets.selectCount(
                        new LambdaQueryWrapper<TicketSku>()
                                .eq(TicketSku::getSessionId, sessionId)
                                .and(
                                        query ->
                                                query.lt(TicketSku::getSaleStartsAt, saleStartsAt)
                                                        .or()
                                                        .gt(TicketSku::getSaleEndsAt, startsAt)));
        if (inconsistent > 0) {
            throw new BusinessException(
                    HttpStatus.CONFLICT, "SESSION_CONFLICT", "新的场次时间与已有票档销售窗口冲突，请先调整票档");
        }
    }

    private ActivitySession requireSession(long id, boolean lock) {
        if (lock) {
            ActivitySession parent = sessions.selectById(id);
            if (parent == null) {
                throw notFound("SESSION_NOT_FOUND", "场次不存在");
            }
            activities.selectOne(
                    new LambdaQueryWrapper<Activity>()
                            .eq(Activity::getId, parent.getActivityId())
                            .last("FOR SHARE"));
        }
        var query = new LambdaQueryWrapper<ActivitySession>().eq(ActivitySession::getId, id);
        if (lock) {
            query.last("FOR UPDATE");
        }
        ActivitySession session = sessions.selectOne(query);
        if (session == null) {
            throw notFound("SESSION_NOT_FOUND", "场次不存在");
        }
        return session;
    }

    private TicketSku requireTicket(long id) {
        TicketSku sku = tickets.selectById(id);
        if (sku == null) {
            throw notFound("TICKET_NOT_FOUND", "票档不存在");
        }
        return sku;
    }

    private void validateSession(ActivitySession session) {
        if (!"SCHEDULED".equals(session.getStatus())
                || !session.getStartsAt().isAfter(LocalDateTime.now(ZoneOffset.UTC))) {
            throw conflict("已取消、已开始或已结束的场次不能创建、编辑或启用票档");
        }
    }

    private void validateWindow(ActivitySession session, TicketWriteRequest request) {
        if (utc(request.saleStartsAt()).isBefore(session.getSaleStartsAt())
                || utc(request.saleEndsAt()).isAfter(session.getStartsAt())) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST, "INVALID_SALE_WINDOW", "票档开售不得早于场次开售，停售不得晚于场次开始");
        }
    }

    private boolean sensitiveFieldsChanged(TicketSku sku, TicketWriteRequest request, long price) {
        return !sku.getName().equals(request.name().trim())
                || sku.getPriceCents() != price
                || !sku.getSaleStartsAt().equals(utc(request.saleStartsAt()))
                || !sku.getSaleEndsAt().equals(utc(request.saleEndsAt()))
                || !sku.getMaxQuantityPerOrder().equals(request.maxQuantityPerOrder());
    }

    private void requireVersion(TicketSku sku, Integer version) {
        if (version == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "VERSION_REQUIRED", "缺少票档版本，请刷新页面");
        }
        if (!sku.getVersion().equals(version)) {
            throw conflict("票档已被修改，请刷新后重试");
        }
    }

    private void requireUniqueName(long sessionId, String name, Long exceptId) {
        var query =
                new LambdaQueryWrapper<TicketSku>()
                        .eq(TicketSku::getSessionId, sessionId)
                        .eq(TicketSku::getName, name);
        if (exceptId != null) {
            query.ne(TicketSku::getId, exceptId);
        }
        if (tickets.selectCount(query) > 0) {
            throw duplicateName();
        }
    }

    private LambdaUpdateWrapper<TicketSku> mutation(TicketSku sku) {
        return new LambdaUpdateWrapper<TicketSku>()
                .eq(TicketSku::getId, sku.getId())
                .eq(TicketSku::getVersion, sku.getVersion())
                .eq(TicketSku::getStatus, sku.getStatus())
                .set(TicketSku::getVersion, sku.getVersion() + 1)
                .set(TicketSku::getUpdatedAt, LocalDateTime.now(ZoneOffset.UTC));
    }

    private TicketView view(TicketSku sku, ActivitySession session) {
        inventory.balance(sku);
        Instant now = Instant.now();
        return TicketView.from(
                sku,
                saleStatus(
                        activities.selectById(session.getActivityId()),
                        session,
                        sku,
                        LocalDateTime.ofInstant(now, ZoneOffset.UTC)),
                now);
    }

    private long price(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_PRICE", "票价超出整数分允许范围");
        }
    }

    private LocalDateTime utc(OffsetDateTime value) {
        return LocalDateTime.ofInstant(value.toInstant(), ZoneOffset.UTC);
    }

    private BusinessException duplicateName() {
        return new BusinessException(HttpStatus.CONFLICT, "TICKET_NAME_CONFLICT", "同一场次已存在同名票档");
    }

    private BusinessException conflict(String message) {
        return new BusinessException(HttpStatus.CONFLICT, "TICKET_CONFLICT", message);
    }

    private BusinessException notFound(String code, String message) {
        return new BusinessException(HttpStatus.NOT_FOUND, code, message);
    }
}
