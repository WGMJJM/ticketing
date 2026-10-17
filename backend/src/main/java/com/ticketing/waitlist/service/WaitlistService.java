package com.ticketing.waitlist.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ticketing.common.api.BusinessException;
import com.ticketing.common.api.PageResponse;
import com.ticketing.order.entity.TicketOrder;
import com.ticketing.order.mapper.OrderMapper;
import com.ticketing.order.service.OrderService;
import com.ticketing.order.service.OrderService.PurchaseContext;
import com.ticketing.ticket.model.SaleStatus;
import com.ticketing.ticket.service.TicketService;
import com.ticketing.waitlist.dto.JoinWaitlistRequest;
import com.ticketing.waitlist.dto.WaitlistView;
import com.ticketing.waitlist.entity.WaitlistEntry;
import com.ticketing.waitlist.mapper.WaitlistMapper;
import com.ticketing.waitlist.messaging.InventoryEventWriter;
import java.time.LocalDateTime;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WaitlistService {
    private final WaitlistMapper waitlists;
    private final OrderService orders;
    private final OrderMapper orderMapper;
    private final InventoryEventWriter events;
    private final int waitMinutes;

    public WaitlistService(WaitlistMapper waitlists, OrderService orders, OrderMapper orderMapper, InventoryEventWriter events,
            @Value("${ticketing.waitlist.max-wait-minutes:60}") int waitMinutes) {
        if (waitMinutes<1) throw new IllegalArgumentException("候补等待时间必须为正数");
        this.waitlists=waitlists; this.orders=orders; this.orderMapper=orderMapper; this.events=events; this.waitMinutes=waitMinutes;
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public WaitlistView join(long userId, JoinWaitlistRequest request) {
        long skuId;
        try { skuId=Long.parseLong(request.skuId()); }
        catch (NumberFormatException exception) { throw new BusinessException(HttpStatus.BAD_REQUEST,"INVALID_ARGUMENT","票档编号超出允许范围"); }
        PurchaseContext context=orders.lockPurchase(userId,skuId,true);
        LocalDateTime now=orderMapper.databaseNow();
        WaitlistEntry active=waitlists.selectOne(new LambdaQueryWrapper<WaitlistEntry>().eq(WaitlistEntry::getUserId,userId)
                .eq(WaitlistEntry::getSkuId,skuId).in(WaitlistEntry::getStatus,"WAITING","ALLOCATED"));
        if (active!=null && "WAITING".equals(active.getStatus()) && !active.getExpiresAt().isAfter(now)) {
            terminal(active,"EXPIRED"); active=null;
        }
        if (active!=null) {
            if (!active.getQuantity().equals(request.quantity())) throw conflict("该票档已有有效候补，不能重复申请不同数量");
            return detail(userId,active.getId());
        }
        int quantity=request.quantity();
        if (!eligible(context,quantity,now)) throw conflict("账号、销售窗口或购票限额不符合候补条件");
        if (available(context)>=quantity) throw conflict("当前库存充足，请直接购买");
        LocalDateTime deadline=now.plusMinutes(waitMinutes);
        if (deadline.isAfter(context.sku().getSaleEndsAt())) deadline=context.sku().getSaleEndsAt();
        WaitlistEntry entry=new WaitlistEntry();
        entry.setUserId(userId); entry.setSkuId(skuId); entry.setQuantity(quantity); entry.setStatus("WAITING");
        entry.setCreatedAt(now); entry.setUpdatedAt(now); entry.setExpiresAt(deadline);
        // SKU锁覆盖ID分配直到提交，入队与分配共享同一互斥范围。
        waitlists.insert(entry);
        events.append(skuId,"WAITLIST_JOINED");
        return detail(userId,entry.getId());
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public WaitlistView cancel(long userId, long id) {
        WaitlistEntry initial=requireOwned(userId,id);
        orders.lockPurchase(userId,initial.getSkuId(),true);
        WaitlistEntry entry=waitlists.selectOne(new LambdaQueryWrapper<WaitlistEntry>().eq(WaitlistEntry::getId,id)
                .eq(WaitlistEntry::getUserId,userId).last("FOR UPDATE"));
        if ("CANCELLED".equals(entry.getStatus())) return detail(userId,id);
        if (!"WAITING".equals(entry.getStatus())) throw conflict("只能取消等待中的候补，已分配请前往对应订单处理");
        terminal(entry,"CANCELLED");
        events.append(entry.getSkuId(),"WAITLIST_CANCELLED");
        return detail(userId,id);
    }

    @Transactional(readOnly=true,isolation=Isolation.READ_COMMITTED)
    public PageResponse<WaitlistView> list(long userId, int page, int size) {
        var items=waitlists.listOwned(userId,size,(long)(page-1)*size);
        items.forEach(this::validateView);
        return new PageResponse<>(items,waitlists.selectCount(new LambdaQueryWrapper<WaitlistEntry>().eq(WaitlistEntry::getUserId,userId)),page,size);
    }

    @Transactional(readOnly=true)
    public WaitlistView detail(long userId, long id) {
        WaitlistView view=waitlists.owned(id,userId);
        if (view==null) throw notFound();
        validateView(view);
        return view;
    }

    /** 一次只处理一个候选用户，释放全部锁后才会选择下一位。 */
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Allocation allocateHead(long skuId, long candidateId) {
        WaitlistEntry initial=waitlists.selectById(candidateId);
        if (initial==null || !initial.getSkuId().equals(skuId)) return Allocation.RETRY;
        PurchaseContext context=orders.lockPurchase(initial.getUserId(),skuId,false);
        WaitlistEntry head=waitlists.head(skuId);
        // 等待SKU锁期间队首可能已经改变，不能拿着旧用户锁替下一位分配。
        if (head==null || !head.getId().equals(candidateId)) return Allocation.RETRY;
        LocalDateTime now=orderMapper.databaseNow();
        if (!head.getExpiresAt().isAfter(now) || !eligible(context,head.getQuantity(),now)) {
            terminal(head,"EXPIRED"); return Allocation.ADVANCED;
        }
        if (available(context)<head.getQuantity()) return Allocation.BLOCKED;
        TicketOrder order=orders.reserveWaitlist(context,head.getQuantity(),head.getId());
        if (!Objects.equals(order.getUserId(),head.getUserId()) || !Objects.equals(order.getSessionId(),context.session().getId())) {
            throw conflict("候补与预留订单的用户或场次不一致");
        }
        TicketOrder owned=orderMapper.owned(order.getId(),head.getUserId());
        if (owned==null || !owned.getSkuId().equals(head.getSkuId()) || !owned.getQuantity().equals(head.getQuantity())) {
            throw conflict("候补与预留订单的票档或数量不一致");
        }
        // 新订单的行锁在候补行锁之前；后续支付/关闭也按订单→候补更新。
        if (waitlists.allocate(head.getId(),order.getId())!=1) throw conflict("候补状态已变化，分配未生效");
        return Allocation.ALLOCATED;
    }

    private boolean eligible(PurchaseContext context, int quantity, LocalDateTime now) {
        SaleStatus status=TicketService.saleStatus(context.activity(),context.session(),context.sku(),now);
        if (!"ACTIVE".equals(context.user().getStatus()) || (status!=SaleStatus.SELLING && status!=SaleStatus.SOLD_OUT)
                || quantity<1 || quantity>context.sku().getMaxQuantityPerOrder()
                || quantity>orders.limits(context.user().getId(),context.session().getId()).remainingQuantity()) return false;
        try { Math.multiplyExact(context.sku().getPriceCents(),(long)quantity); }
        catch (ArithmeticException exception) { return false; }
        return true;
    }
    private int available(PurchaseContext context) { return context.sku().getTotalStock()-context.sku().getLockedStock()-context.sku().getSoldStock(); }
    private WaitlistEntry requireOwned(long userId,long id) {
        WaitlistEntry entry=waitlists.selectOne(new LambdaQueryWrapper<WaitlistEntry>().eq(WaitlistEntry::getId,id).eq(WaitlistEntry::getUserId,userId));
        if (entry==null) throw notFound();
        return entry;
    }
    private void terminal(WaitlistEntry entry,String status) {
        if (waitlists.update(null,new LambdaUpdateWrapper<WaitlistEntry>().eq(WaitlistEntry::getId,entry.getId())
                .eq(WaitlistEntry::getStatus,"WAITING").set(WaitlistEntry::getStatus,status)
                .set(WaitlistEntry::getUpdatedAt,orderMapper.databaseNow()))!=1) throw conflict("候补状态已变化");
    }
    private void validateView(WaitlistView view) {
        if (view.reservedOrderId()!=null && view.orderStatus()==null) throw conflict("候补与预留订单关联不一致");
    }
    private BusinessException conflict(String message) { return new BusinessException(HttpStatus.CONFLICT,"WAITLIST_CONFLICT",message); }
    private BusinessException notFound() { return new BusinessException(HttpStatus.NOT_FOUND,"WAITLIST_NOT_FOUND","候补记录不存在"); }
    public enum Allocation { ALLOCATED, ADVANCED, RETRY, BLOCKED }
}
