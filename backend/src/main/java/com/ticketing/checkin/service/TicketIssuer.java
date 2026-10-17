package com.ticketing.checkin.service;

import com.ticketing.checkin.entity.ElectronicTicket;
import com.ticketing.checkin.mapper.ElectronicTicketMapper;
import com.ticketing.checkin.security.TicketCredentialSigner;
import com.ticketing.common.api.BusinessException;
import java.security.MessageDigest;
import java.util.HashSet;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TicketIssuer {
    private final ElectronicTicketMapper tickets;
    private final TicketCredentialSigner signer;
    public TicketIssuer(ElectronicTicketMapper tickets, TicketCredentialSigner signer) {
        this.tickets = tickets;
        this.signer = signer;
    }

    /** 调用方已按用户→父级→SKU→订单加锁；票券作为订单的末端子资源处理。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public int issuePaidOrder(long orderId) {
        var source = tickets.source(orderId);
        if (source == null || !"PAID".equals(source.status())) throw conflict("只有已支付订单可以签发电子票");
        var existing = tickets.lockIssued(orderId);
        var sequences = new HashSet<Integer>();
        for (ElectronicTicket ticket : existing) {
            if (ticket.getOrderItemId() != source.orderItemId() || ticket.getOwnerId() != source.ownerId()
                    || ticket.getSkuId() != source.skuId() || ticket.getSequenceNo() < 1 || ticket.getSequenceNo() > source.quantity()
                    || !sequences.add(ticket.getSequenceNo())) throw conflict("电子票数量或订单关联异常，补票未执行");
            String credential = signer.credential(ticket.getTicketNo(), ticket.getCredentialKeyVersion());
            if (!MessageDigest.isEqual(signer.digest(credential), ticket.getCredentialDigest())) {
                throw conflict("已有电子票凭证校验失败，补票未执行");
            }
        }
        int created = 0;
        for (int sequence = 1; sequence <= source.quantity(); sequence++) {
            if (sequences.contains(sequence)) continue;
            ElectronicTicket ticket = new ElectronicTicket();
            ticket.setTicketNo(signer.newTicketNo());
            ticket.setCredentialKeyVersion(signer.currentVersion());
            ticket.setCredentialDigest(signer.digest(signer.credential(ticket.getTicketNo(), ticket.getCredentialKeyVersion())));
            ticket.setOrderId(orderId);
            ticket.setOrderItemId(source.orderItemId());
            ticket.setSkuId(source.skuId());
            ticket.setOwnerId(source.ownerId());
            ticket.setSequenceNo(sequence);
            ticket.setStatus("UNUSED");
            if (tickets.insert(ticket) != 1) throw conflict("电子票写入未完成，出票事务已回滚");
            created++;
        }
        return created;
    }

    private BusinessException conflict(String message) { return new BusinessException(HttpStatus.CONFLICT, "TICKET_ISSUANCE_CONFLICT", message); }
}
