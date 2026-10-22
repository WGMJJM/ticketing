package com.ticketing.order.service;

import com.ticketing.common.api.BusinessException;
import com.ticketing.common.api.PageResponse;
import com.ticketing.order.dto.AdminOrderDetail;
import com.ticketing.order.dto.AdminOrderQuery;
import com.ticketing.order.dto.AdminOrderView;
import com.ticketing.order.dto.OrderView;
import com.ticketing.order.entity.TicketOrder;
import com.ticketing.order.mapper.OrderMapper;
import com.ticketing.order.mapper.AdminOrderMapper;
import com.ticketing.order.mapper.RefundMapper;
import com.ticketing.order.mapper.AftercareMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.time.ZoneOffset;
import java.time.Instant;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class AdminOrderService {
    private final OrderMapper orders;
    private final AdminOrderMapper admin;
    private final RefundMapper refunds;
    private final AftercareMapper aftercare;

    public AdminOrderService(OrderMapper orders, AdminOrderMapper admin, RefundMapper refunds, AftercareMapper aftercare) {
        this.orders = orders;
        this.admin = admin;
        this.refunds = refunds;
        this.aftercare = aftercare;
    }

    public PageResponse<AdminOrderView> list(AdminOrderQuery query) {
        query.validateRange();
        Instant now = orders.databaseNow().toInstant(ZoneOffset.UTC);
        int page = query.pageNumber();
        int size = query.pageSize();
        return new PageResponse<>(admin.list(query, size, (long) (page - 1) * size).stream()
                .map(order -> summary(order, now)).toList(), admin.count(query), page, size);
    }

    public AdminOrderDetail detail(long id) {
        TicketOrder order = orders.detail(id);
        if (order == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "订单不存在或明细缺失");
        }
        return new AdminOrderDetail(summary(order, orders.databaseNow().toInstant(ZoneOffset.UTC)),
                admin.waitlist(id), admin.tickets(id), refunds.find(id), aftercare.find(id));
    }

    private AdminOrderView summary(TicketOrder order, Instant now) {
        return new AdminOrderView(OrderView.from(order, now), order.getUserId().toString(),
                order.getUsername(), order.getPaidAt() == null ? null : order.getPaidAt().toInstant(ZoneOffset.UTC));
    }
}
