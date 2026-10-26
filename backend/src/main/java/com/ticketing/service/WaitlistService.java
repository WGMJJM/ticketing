package com.ticketing.service;

import com.ticketing.common.PageResponse;
import com.ticketing.config.WaitlistProperties;
import com.ticketing.dto.JoinWaitlistRequest;
import com.ticketing.dto.TradeContext;
import com.ticketing.entity.WaitlistEntry;
import com.ticketing.enums.InventoryChangeReason;
import com.ticketing.enums.WaitlistStatus;
import com.ticketing.exception.BusinessException;
import com.ticketing.mapper.WaitlistMapper;
import com.ticketing.messaging.InventoryEvents;
import com.ticketing.vo.WaitlistView;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class WaitlistService {
    private final WaitlistMapper waitlists;
    private final TradeResourceLocker resources;
    private final OrderReservation reservation;
    private final InventoryEvents events;
    private final int waitMinutes;
    private final RedisStockReservationService stocks;

    public WaitlistService(
            WaitlistMapper waitlists,
            TradeResourceLocker resources,
            OrderReservation reservation,
            InventoryEvents events,
            WaitlistProperties properties, RedisStockReservationService stocks) {
        this.waitlists = waitlists;
        this.resources = resources;
        this.reservation = reservation;
        this.events = events;
        this.waitMinutes = properties.maxWaitMinutes();
        this.stocks = stocks;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public WaitlistView join(long userId, JoinWaitlistRequest request) {
        long skuId;
        try {
            skuId = Long.parseLong(request.skuId());
        } catch (NumberFormatException exception) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "票档编号超出允许范围");
        }
        TradeContext context = resources.lockWaitlistPurchase(userId, skuId, true);
        LocalDateTime now = waitlists.databaseNow();
        WaitlistEntry active =
                waitlists.selectOne(
                        new LambdaQueryWrapper<WaitlistEntry>()
                                .eq(WaitlistEntry::getUserId, userId)
                                .eq(WaitlistEntry::getSkuId, skuId)
                                .in(
                                        WaitlistEntry::getStatus,
                                        WaitlistStatus.WAITING.name(),
                                        WaitlistStatus.PROCESSING.name(),
                                        WaitlistStatus.ALLOCATED.name()));
        if (active != null
                && WaitlistStatus.WAITING.name().equals(active.getStatus())
                && !active.getExpiresAt().isAfter(now)) {
            terminal(active, WaitlistStatus.EXPIRED);
            active = null;
        }
        if (active != null) {
            if (!active.getQuantity().equals(request.quantity())) {
                throw conflict("该票档已有有效候补，不能重复申请不同数量");
            }
            return view(userId, active.getId());
        }
        int quantity = request.quantity();
        if (!reservation.eligibleForWaitlist(context, quantity, now)) {
            throw conflict("账号、销售窗口或购票限额不符合候补条件");
        }
        // 和普通预扣在同一 Lua 原子边界检查余额并开启优先门禁，先门禁后提交队列。
        stocks.joinPriority(skuId, quantity, waitlists.head(skuId) != null);
        LocalDateTime deadline = now.plusMinutes(waitMinutes);
        if (deadline.isAfter(context.sale().saleEndsAt())) {
            deadline = context.sale().saleEndsAt();
        }
        WaitlistEntry entry = new WaitlistEntry();
        entry.setUserId(userId);
        entry.setSkuId(skuId);
        entry.setQuantity(quantity);
        entry.setStatus(WaitlistStatus.WAITING.name());
        entry.setAttemptNo(0);
        entry.setCreatedAt(now);
        entry.setUpdatedAt(now);
        entry.setExpiresAt(deadline);
        // SKU锁覆盖ID分配直到提交，入队与分配共享同一互斥范围。
        waitlists.insert(entry);
        events.append(skuId, InventoryChangeReason.WAITLIST_JOINED);
        return view(userId, entry.getId());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public WaitlistView cancel(long userId, long id) {
        WaitlistEntry initial = requireOwned(userId, id);
        resources.lockWaitlistPurchase(userId, initial.getSkuId(), true);
        WaitlistEntry entry =
                waitlists.selectOne(
                        new LambdaQueryWrapper<WaitlistEntry>()
                                .eq(WaitlistEntry::getId, id)
                                .eq(WaitlistEntry::getUserId, userId)
                                .last("FOR UPDATE"));
        if (WaitlistStatus.CANCELLED.name().equals(entry.getStatus())) {
            return view(userId, id);
        }
        if (!WaitlistStatus.WAITING.name().equals(entry.getStatus())) {
            throw conflict("仅等待中的候补可取消；分配中请等待结果，已分配请处理对应订单");
        }
        terminal(entry, WaitlistStatus.CANCELLED);
        events.append(entry.getSkuId(), InventoryChangeReason.WAITLIST_CANCELLED);
        return view(userId, id);
    }

    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public PageResponse<WaitlistView> list(long userId, int page, int size) {
        var items = waitlists.listOwned(userId, size, (long) (page - 1) * size);
        items.forEach(this::validateView);
        return new PageResponse<>(
                items,
                waitlists.selectCount(
                        new LambdaQueryWrapper<WaitlistEntry>()
                                .eq(WaitlistEntry::getUserId, userId)),
                page,
                size);
    }

    @Transactional(readOnly = true)
    public WaitlistView detail(long userId, long id) {
        return view(userId, id);
    }

    private WaitlistView view(long userId, long id) {
        WaitlistView view = waitlists.owned(id, userId);
        if (view == null) {
            throw notFound();
        }
        validateView(view);
        return view;
    }

    /** 只选取队首并登记尝试，不创建订单、不预扣库存、不等待 MQ。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public WaitlistEntry prepare(long skuId) {
        WaitlistEntry initial = waitlists.head(skuId);
        if (initial == null) {
            resources.lockSale(skuId);
            if (waitlists.head(skuId) == null) stocks.closePriority(skuId);
            return null;
        }
        TradeContext context = resources.lockWaitlistPurchase(initial.getUserId(), skuId, false);
        WaitlistEntry head = waitlists.head(skuId);
        if (head == null || !head.getId().equals(initial.getId())) return null;
        if ("PROCESSING".equals(head.getStatus())) {
            stocks.selectWaitlist(skuId, head.getId(), head.getAttemptNo());
            return head;
        }
        LocalDateTime now = waitlists.databaseNow();
        if (!head.getExpiresAt().isAfter(now) || !reservation.eligibleForWaitlist(context, head.getQuantity(), now)) {
            terminal(head, WaitlistStatus.EXPIRED);
            return null;
        }
        stocks.selectWaitlist(skuId, head.getId(), head.getAttemptNo());
        long requestId = com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
        if (waitlists.start(head.getId(), requestId) != 1) throw conflict("候补状态已变化");
        head.setStatus("PROCESSING");
        head.setPurchaseRequestId(requestId);
        return head;
    }

    /** 仅无预扣的明确拒绝，或已提交终态且 Redis 已结算后才可推进尝试号。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void finishAttempt(long id, long requestId, boolean retryable) {
        WaitlistEntry initial = waitlists.selectById(id);
        if (initial == null) return;
        TradeContext context = resources.lockWaitlistPurchase(initial.getUserId(), initial.getSkuId(), false);
        WaitlistEntry head = waitlists.head(initial.getSkuId());
        if (head == null || !head.getId().equals(id) || !"PROCESSING".equals(head.getStatus())
                || !java.util.Objects.equals(head.getPurchaseRequestId(), requestId)) return;
        if (!stocks.finishWaitlistAttempt(head.getSkuId(), head.getId(), head.getAttemptNo())) return;
        LocalDateTime now = waitlists.databaseNow();
        boolean waiting = retryable && head.getExpiresAt().isAfter(now)
                && reservation.eligibleForWaitlist(context, head.getQuantity(), now);
        waitlists.finishAttempt(id, requestId, waiting ? "WAITING" : "EXPIRED");
    }

    private WaitlistEntry requireOwned(long userId, long id) {
        WaitlistEntry entry =
                waitlists.selectOne(
                        new LambdaQueryWrapper<WaitlistEntry>()
                                .eq(WaitlistEntry::getId, id)
                                .eq(WaitlistEntry::getUserId, userId));
        if (entry == null) {
            throw notFound();
        }
        return entry;
    }

    private void terminal(WaitlistEntry entry, WaitlistStatus status) {
        if (waitlists.finishWaiting(entry.getId(), status.name()) != 1) {
            throw conflict("候补状态已变化");
        }
    }

    private void validateView(WaitlistView view) {
        if (view.reservedOrderId() != null && view.orderStatus() == null) {
            throw conflict("候补与预留订单关联不一致");
        }
    }

    private BusinessException conflict(String message) {
        return new BusinessException(HttpStatus.CONFLICT, "WAITLIST_CONFLICT", message);
    }

    private BusinessException notFound() {
        return new BusinessException(HttpStatus.NOT_FOUND, "WAITLIST_NOT_FOUND", "候补记录不存在");
    }

}
