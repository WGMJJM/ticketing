package com.ticketing.checkin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ticketing.auth.mapper.UserAccountMapper;
import com.ticketing.checkin.dto.ElectronicTicketView;
import com.ticketing.checkin.dto.TicketCredentialView;
import com.ticketing.checkin.entity.ElectronicTicket;
import com.ticketing.checkin.mapper.ElectronicTicketMapper;
import com.ticketing.checkin.security.TicketCredentialSigner;
import com.ticketing.common.api.BusinessException;
import com.ticketing.common.api.PageResponse;
import com.ticketing.order.entity.TicketOrder;
import com.ticketing.order.mapper.OrderMapper;
import com.ticketing.order.service.OrderService;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ElectronicTicketService {
    private final ElectronicTicketMapper tickets;
    private final TicketCredentialSigner signer;
    private final TicketIssuer issuer;
    private final OrderService orders;
    private final OrderMapper orderMapper;
    private final UserAccountMapper users;
    private final int opensMinutes;
    public ElectronicTicketService(ElectronicTicketMapper tickets, TicketCredentialSigner signer, TicketIssuer issuer,
            OrderService orders, OrderMapper orderMapper, UserAccountMapper users,
            @Value("${ticketing.checkin.opens-before-minutes:60}") int opensMinutes) {
        if (opensMinutes < 0 || opensMinutes > 1440) throw new IllegalArgumentException("核销开放时间须为演出前0至1440分钟");
        this.tickets = tickets;
        this.signer = signer;
        this.issuer = issuer;
        this.orders = orders;
        this.orderMapper = orderMapper;
        this.users = users;
        this.opensMinutes = opensMinutes;
    }

    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public PageResponse<ElectronicTicketView> list(long ownerId, Long orderId, String status, int page, int size) {
        if (orderId != null && orderMapper.owned(orderId, ownerId) == null) throw notFound();
        return new PageResponse<>(tickets.listOwned(ownerId, orderId, status, size, (long) (page - 1) * size),
                tickets.countOwned(ownerId, orderId, status), page, size);
    }

    @Transactional(readOnly = true)
    public ElectronicTicketView detail(long ownerId, long id) {
        ElectronicTicketView view = tickets.owned(id, ownerId);
        if (view == null) throw notFound();
        return view;
    }

    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public TicketCredentialView credential(long ownerId, long id) {
        ElectronicTicket ticket = tickets.selectOne(new LambdaQueryWrapper<ElectronicTicket>()
                .eq(ElectronicTicket::getId, id).eq(ElectronicTicket::getOwnerId, ownerId));
        if (ticket == null) throw notFound();
        requireUnused(ticket);
        TicketOrder order = orderMapper.selectById(ticket.getOrderId());
        if (order == null || !"PAID".equals(order.getStatus())) throw conflict("TICKET_ORDER_UNPAID", "订单未支付，不能展示入场凭证");
        String credential = signer.credential(ticket.getTicketNo(), ticket.getCredentialKeyVersion());
        if (!MessageDigest.isEqual(signer.digest(credential), ticket.getCredentialDigest())) throw TicketCredentialSigner.invalid();
        return new TicketCredentialView(Long.toString(id), credential);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ElectronicTicketView checkIn(long adminId, String credential) {
        var verified = signer.verify(credential);
        ElectronicTicket initial = tickets.selectOne(new LambdaQueryWrapper<ElectronicTicket>().eq(ElectronicTicket::getTicketNo, verified.ticketNo()));
        if (initial == null) throw TicketCredentialSigner.invalid();
        // 核销人员行在最前；绝不在持有订单/票券锁后再回锁用户或父级。
        var context = orders.lockPurchase(adminId, initial.getSkuId(), true);
        if (!"ADMIN".equals(users.selectById(adminId).getRole())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "当前账号没有电子票核销权限");
        }
        TicketOrder order = lockOrder(initial.getOrderId());
        ElectronicTicket ticket = tickets.selectById(initial.getId());
        if (ticket.getCredentialKeyVersion() != verified.keyVersion()
                || !MessageDigest.isEqual(ticket.getCredentialDigest(), verified.digest())) throw TicketCredentialSigner.invalid();
        requireUnused(ticket);
        if (!"PAID".equals(order.getStatus())) throw conflict("TICKET_ORDER_UNPAID", "订单未支付，不能核销");
        if (!"SCHEDULED".equals(context.session().getStatus())) throw conflict("CHECKIN_SESSION_UNAVAILABLE", "场次已取消或结束，不能核销");
        // 最终条件使用MySQL实际时间，等待锁不能越过核销窗口。
        if (tickets.checkIn(ticket.getId(), adminId, opensMinutes) != 1) {
            throw conflict("CHECKIN_UNAVAILABLE", "当前不在核销时间内，或电子票状态已变化，请刷新后重试");
        }
        return tickets.view(ticket.getId());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public int backfill(long orderId) {
        var initial = tickets.source(orderId);
        if (initial == null) throw notFound();
        orders.lockPurchase(initial.ownerId(), initial.skuId(), false);
        TicketOrder order = lockOrder(orderId);
        if (!order.getUserId().equals(initial.ownerId())) throw conflict("TICKET_ISSUANCE_CONFLICT", "历史订单归属异常，补票未执行");
        return issuer.issuePaidOrder(orderId);
    }

    private TicketOrder lockOrder(long id) {
        return orderMapper.selectOne(new LambdaQueryWrapper<TicketOrder>().eq(TicketOrder::getId, id).last("FOR UPDATE"));
    }
    private void requireUnused(ElectronicTicket ticket) {
        if ("USED".equals(ticket.getStatus())) throw conflict("TICKET_ALREADY_USED", "电子票已核销，请勿重复入场");
        if (!"UNUSED".equals(ticket.getStatus())) throw conflict("TICKET_VOID", "电子票已作废，不能作为入场凭证");
    }
    private BusinessException notFound() { return new BusinessException(HttpStatus.NOT_FOUND, "ELECTRONIC_TICKET_NOT_FOUND", "电子票或所属订单不存在"); }
    private BusinessException conflict(String code, String message) { return new BusinessException(HttpStatus.CONFLICT, code, message); }
}
