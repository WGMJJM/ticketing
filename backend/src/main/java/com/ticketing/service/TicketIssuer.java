package com.ticketing.service;

import com.ticketing.entity.ElectronicTicket;
import com.ticketing.mapper.ElectronicTicketMapper;
import com.ticketing.model.ElectronicTicketStatus;
import com.ticketing.security.TicketCredentialSigner;
import com.ticketing.common.BusinessException;
import com.ticketing.api.OrderLifecycle;
import com.ticketing.model.OrderStatus;

import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.util.HashSet;

@Service
@Order(OrderLifecycle.TICKET_PHASE)
public class TicketIssuer implements OrderLifecycle {
    private final ElectronicTicketMapper tickets;
    private final TicketCredentialSigner signer;

    public TicketIssuer(ElectronicTicketMapper tickets, TicketCredentialSigner signer) {
        this.tickets = tickets;
        this.signer = signer;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void onTransition(Transition transition) {
        if (transition.target() == OrderStatus.PAID) {
            issue(transition.orderId());
        } else if (transition.target() == OrderStatus.REFUNDED
                && tickets.voidUnused(transition.orderId()) != transition.quantity()) {
            throw conflict("电子票作废数量异常，退款事务已回滚");
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public RefundBlock refundBlocker(Transition transition) {
        var source = tickets.source(transition.orderId());
        var existing = tickets.lockIssued(transition.orderId());
        if (source == null || source.ownerId() != transition.userId()
                || source.skuId() != transition.skuId() || source.quantity() != transition.quantity()
                || existing.size() != transition.quantity()) {
            return new RefundBlock("TICKET_RELATION_INVALID", "电子票数量或订单关联异常，需要人工处理");
        }
        var sequences = new HashSet<Integer>();
        for (ElectronicTicket ticket : existing) {
            if (ElectronicTicketStatus.USED.name().equals(ticket.getStatus())) {
                return new RefundBlock("TICKET_ALREADY_USED", "订单包含已核销电子票，需要人工处理，核销记录保持不变");
            }
            if (!ElectronicTicketStatus.UNUSED.name().equals(ticket.getStatus())
                    || ticket.getOwnerId() != transition.userId()
                    || ticket.getOrderItemId() != source.orderItemId()
                    || ticket.getSkuId() != transition.skuId()
                    || ticket.getSequenceNo() < 1 || ticket.getSequenceNo() > transition.quantity()
                    || !sequences.add(ticket.getSequenceNo())) {
                return new RefundBlock("TICKET_RELATION_INVALID", "电子票状态或明细关联异常，需要人工处理");
            }
            try {
                String credential = signer.credential(ticket.getTicketNo(), ticket.getCredentialKeyVersion());
                if (!MessageDigest.isEqual(signer.digest(credential), ticket.getCredentialDigest())) {
                    return new RefundBlock("TICKET_DIGEST_INVALID", "电子票凭证摘要异常，需要人工处理");
                }
            } catch (BusinessException exception) {
                return new RefundBlock("TICKET_KEY_UNAVAILABLE", "电子票签名版本暂不可用，需要人工处理");
            }
        }
        return null;
    }

    /** 调用方已按用户→父级→SKU→订单加锁；票券作为订单的末端子资源处理。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public int issuePaidOrder(long orderId) {
        return issue(orderId);
    }

    private int issue(long orderId) {
        var source = tickets.source(orderId);
        if (source == null || !OrderStatus.PAID.name().equals(source.status())) {
            throw conflict("只有已支付订单可以签发电子票");
        }
        var existing = tickets.lockIssued(orderId);
        var sequences = new HashSet<Integer>();
        for (ElectronicTicket ticket : existing) {
            if (ticket.getOrderItemId() != source.orderItemId()
                    || ticket.getOwnerId() != source.ownerId()
                    || ticket.getSkuId() != source.skuId()
                    || ticket.getSequenceNo() < 1
                    || ticket.getSequenceNo() > source.quantity()
                    || !sequences.add(ticket.getSequenceNo())) {
                throw conflict("电子票数量或订单关联异常，补票未执行");
            }
            String credential =
                    signer.credential(ticket.getTicketNo(), ticket.getCredentialKeyVersion());
            if (!MessageDigest.isEqual(signer.digest(credential), ticket.getCredentialDigest())) {
                throw conflict("已有电子票凭证校验失败，补票未执行");
            }
        }
        int created = 0;
        for (int sequence = 1; sequence <= source.quantity(); sequence++) {
            if (sequences.contains(sequence)) {
                continue;
            }
            ElectronicTicket ticket = new ElectronicTicket();
            ticket.setTicketNo(signer.newTicketNo());
            ticket.setCredentialKeyVersion(signer.currentVersion());
            ticket.setCredentialDigest(
                    signer.digest(
                            signer.credential(
                                    ticket.getTicketNo(), ticket.getCredentialKeyVersion())));
            ticket.setOrderId(orderId);
            ticket.setOrderItemId(source.orderItemId());
            ticket.setSkuId(source.skuId());
            ticket.setOwnerId(source.ownerId());
            ticket.setSequenceNo(sequence);
            ticket.setStatus(ElectronicTicketStatus.UNUSED.name());
            if (tickets.insert(ticket) != 1) {
                throw conflict("电子票写入未完成，出票事务已回滚");
            }
            created++;
        }
        return created;
    }

    private BusinessException conflict(String message) {
        return new BusinessException(HttpStatus.CONFLICT, "TICKET_ISSUANCE_CONFLICT", message);
    }
}
