package com.ticketing.service;

import com.ticketing.common.PageResponse;
import com.ticketing.mapper.TicketNotificationMapper;
import com.ticketing.vo.TicketNotificationView;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TicketNotificationService {
    private final TicketNotificationMapper notifications;
    private final boolean mailEnabled;

    public TicketNotificationService(TicketNotificationMapper notifications,
            @Value("${ticketing.notifications.mail-enabled:false}") boolean mailEnabled) {
        this.notifications = notifications;
        this.mailEnabled = mailEnabled;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void waitlistAllocated(long userId, long orderId) {
        notifications.waitlistAllocated(userId, orderId, mailEnabled);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void sessionCancelled(long userId, long orderId) {
        notifications.sessionCancelled(userId, orderId);
    }

    public PageResponse<TicketNotificationView> list(long userId, boolean unread, int page, int size) {
        return new PageResponse<>(notifications.list(userId, unread, size, (long) (page - 1) * size),
                notifications.count(userId, unread), page, size);
    }

    public long unreadCount(long userId) { return notifications.count(userId, true); }

    public void markRead(long userId, long id) { notifications.markRead(userId, id); }

    public void markAllRead(long userId) { notifications.markAllRead(userId); }
}
