package com.ticketing.service;

import com.ticketing.common.PageResponse;
import com.ticketing.config.CheckinProperties;
import com.ticketing.entity.ElectronicTicket;
import com.ticketing.enums.ElectronicTicketStatus;
import com.ticketing.enums.OrderStatus;
import com.ticketing.enums.SessionStatus;
import com.ticketing.exception.BusinessException;
import com.ticketing.mapper.ElectronicTicketMapper;
import com.ticketing.security.TicketCredentialSigner;
import com.ticketing.vo.ElectronicTicketView;
import com.ticketing.vo.TicketCredentialView;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;

@Service
public class ElectronicTicketService {
    private final ElectronicTicketMapper tickets;
    private final TicketCredentialSigner signer;
    private final TicketIssuer issuer;
    private final TradeResourceLocker resources;
    private final StaffService staff;
    private final int opensMinutes;

    public ElectronicTicketService(
            ElectronicTicketMapper tickets,
            TicketCredentialSigner signer,
            TicketIssuer issuer,
            TradeResourceLocker resources,
            CheckinProperties properties,
            StaffService staff) {
        this.tickets = tickets;
        this.signer = signer;
        this.issuer = issuer;
        this.resources = resources;
        this.staff = staff;
        this.opensMinutes = properties.opensBeforeMinutes();
    }

    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public PageResponse<ElectronicTicketView> list(
            long ownerId, Long orderId, String status, int page, int size) {
        if (orderId != null) {
            var source = tickets.source(orderId);
            if (source == null || source.ownerId() != ownerId) {
                throw notFound();
            }
        }
        return new PageResponse<>(
                tickets.listOwned(ownerId, orderId, status, size, (long) (page - 1) * size),
                tickets.countOwned(ownerId, orderId, status),
                page,
                size);
    }

    @Transactional(readOnly = true)
    public ElectronicTicketView detail(long ownerId, long id) {
        ElectronicTicketView view = tickets.owned(id, ownerId);
        if (view == null) {
            throw notFound();
        }
        return view;
    }

    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public TicketCredentialView credential(long ownerId, long id) {
        ElectronicTicket ticket =
                tickets.selectOne(
                        new LambdaQueryWrapper<ElectronicTicket>()
                                .eq(ElectronicTicket::getId, id)
                                .eq(ElectronicTicket::getOwnerId, ownerId));
        if (ticket == null) {
            throw notFound();
        }
        requireUnused(ticket);
        var order = tickets.source(ticket.getOrderId());
        if (order == null || !OrderStatus.PAID.name().equals(order.status())) {
            throw conflict("TICKET_ORDER_UNPAID", "订单未支付，不能展示入场凭证");
        }
        String credential =
                signer.credential(ticket.getTicketNo(), ticket.getCredentialKeyVersion());
        if (!MessageDigest.isEqual(signer.digest(credential), ticket.getCredentialDigest())) {
            throw TicketCredentialSigner.invalid();
        }
        return new TicketCredentialView(Long.toString(id), credential);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ElectronicTicketView checkIn(long operatorId, String credential, boolean allowStaff) {
        var verified = signer.verify(credential);
        ElectronicTicket initial =
                tickets.selectOne(
                        new LambdaQueryWrapper<ElectronicTicket>()
                                .eq(ElectronicTicket::getTicketNo, verified.ticketNo()));
        if (initial == null) {
            throw TicketCredentialSigner.invalid();
        }
        // 核销人员行在最前；绝不在持有订单/票券锁后再回锁用户或父级。
        var context = resources.lockPurchase(operatorId, initial.getSkuId(), true);
        if (!context.admin()) {
            if (!allowStaff) {
                throw new BusinessException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "当前账号没有管理员核销权限");
            }
            staff.requirePermission(operatorId, context.sale().sessionId());
        }
        OrderStatus order = resources.lockOrder(initial.getOrderId(), initial.getOwnerId());
        ElectronicTicket ticket = tickets.selectById(initial.getId());
        if (ticket.getCredentialKeyVersion() != verified.keyVersion()
                || !MessageDigest.isEqual(ticket.getCredentialDigest(), verified.digest())) {
            throw TicketCredentialSigner.invalid();
        }
        requireUnused(ticket);
        if (order != OrderStatus.PAID) {
            throw conflict("TICKET_ORDER_UNPAID", "订单未支付，不能核销");
        }
        if (context.sale().sessionStatus() != SessionStatus.SCHEDULED) {
            throw conflict("CHECKIN_SESSION_UNAVAILABLE", "场次已取消或结束，不能核销");
        }
        // 最终条件使用MySQL实际时间，等待锁不能越过核销窗口。
        if (tickets.checkIn(ticket.getId(), operatorId, opensMinutes) != 1) {
            throw conflict("CHECKIN_UNAVAILABLE", "当前不在核销时间内，或电子票状态已变化，请刷新后重试");
        }
        return tickets.view(ticket.getId());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public int backfill(long orderId) {
        var initial = tickets.source(orderId);
        if (initial == null) {
            throw notFound();
        }
        resources.lockPurchase(initial.ownerId(), initial.skuId(), false);
        resources.lockOrder(orderId, initial.ownerId());
        return issuer.issuePaidOrder(orderId);
    }

    private void requireUnused(ElectronicTicket ticket) {
        if (ElectronicTicketStatus.USED.name().equals(ticket.getStatus())) {
            throw conflict("TICKET_ALREADY_USED", "电子票已核销，请勿重复入场");
        }
        if (!ElectronicTicketStatus.UNUSED.name().equals(ticket.getStatus())) {
            throw conflict("TICKET_VOID", "电子票已作废，不能作为入场凭证");
        }
    }

    private BusinessException notFound() {
        return new BusinessException(
                HttpStatus.NOT_FOUND, "ELECTRONIC_TICKET_NOT_FOUND", "电子票或所属订单不存在");
    }

    private BusinessException conflict(String code, String message) {
        return new BusinessException(HttpStatus.CONFLICT, code, message);
    }
}
