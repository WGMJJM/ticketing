package com.ticketing.waitlist.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ticketing.common.api.BusinessException;
import com.ticketing.common.api.PageResponse;
import com.ticketing.messaging.InventoryEvents;
import com.ticketing.messaging.model.InventoryChangeReason;
import com.ticketing.order.api.OrderReservation;
import com.ticketing.order.api.TradeContext;
import com.ticketing.order.api.TradeResourceLocker;
import com.ticketing.waitlist.config.WaitlistProperties;
import com.ticketing.waitlist.dto.JoinWaitlistRequest;
import com.ticketing.waitlist.dto.WaitlistView;
import com.ticketing.waitlist.entity.WaitlistEntry;
import com.ticketing.waitlist.mapper.WaitlistMapper;
import com.ticketing.waitlist.model.WaitlistStatus;

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

    public WaitlistService(
            WaitlistMapper waitlists,
            TradeResourceLocker resources,
            OrderReservation reservation,
            InventoryEvents events,
            WaitlistProperties properties) {
        this.waitlists = waitlists;
        this.resources = resources;
        this.reservation = reservation;
        this.events = events;
        this.waitMinutes = properties.maxWaitMinutes();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public WaitlistView join(long userId, JoinWaitlistRequest request) {
        long skuId;
        try {
            skuId = Long.parseLong(request.skuId());
        } catch (NumberFormatException exception) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "票档编号超出允许范围");
        }
        TradeContext context = resources.lockPurchase(userId, skuId, true);
        LocalDateTime now = waitlists.databaseNow();
        WaitlistEntry active =
                waitlists.selectOne(
                        new LambdaQueryWrapper<WaitlistEntry>()
                                .eq(WaitlistEntry::getUserId, userId)
                                .eq(WaitlistEntry::getSkuId, skuId)
                                .in(
                                        WaitlistEntry::getStatus,
                                        WaitlistStatus.WAITING.name(),
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
        if (context.sale().availableStock() >= quantity) {
            throw conflict("当前库存充足，请直接购买");
        }
        LocalDateTime deadline = now.plusMinutes(waitMinutes);
        if (deadline.isAfter(context.sale().saleEndsAt())) {
            deadline = context.sale().saleEndsAt();
        }
        WaitlistEntry entry = new WaitlistEntry();
        entry.setUserId(userId);
        entry.setSkuId(skuId);
        entry.setQuantity(quantity);
        entry.setStatus(WaitlistStatus.WAITING.name());
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
        resources.lockPurchase(userId, initial.getSkuId(), true);
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
            throw conflict("只能取消等待中的候补，已分配请前往对应订单处理");
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

    /** 一次只处理一个候选用户，释放全部锁后才会选择下一位。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Allocation allocateHead(long skuId, long candidateId) {
        WaitlistEntry initial = waitlists.selectById(candidateId);
        if (initial == null || !initial.getSkuId().equals(skuId)) {
            return Allocation.RETRY;
        }
        TradeContext context = resources.lockPurchase(initial.getUserId(), skuId, false);
        WaitlistEntry head = waitlists.head(skuId);
        // 等待SKU锁期间队首可能已经改变，不能拿着旧用户锁替下一位分配。
        if (head == null || !head.getId().equals(candidateId)) {
            return Allocation.RETRY;
        }
        LocalDateTime now = waitlists.databaseNow();
        if (!head.getExpiresAt().isAfter(now)
                || !reservation.eligibleForWaitlist(context, head.getQuantity(), now)) {
            terminal(head, WaitlistStatus.EXPIRED);
            return Allocation.ADVANCED;
        }
        if (context.sale().availableStock() < head.getQuantity()) {
            return Allocation.BLOCKED;
        }
        long orderId = reservation.reserveWaitlist(context, head.getQuantity(), head.getId());
        // 新订单的行锁在候补行锁之前；后续支付/关闭也按订单→候补更新。
        if (waitlists.allocate(head.getId(), orderId) != 1) {
            throw conflict("候补状态已变化，分配未生效");
        }
        return Allocation.ALLOCATED;
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

    public enum Allocation {
        ALLOCATED,
        ADVANCED,
        RETRY,
        BLOCKED
    }
}
