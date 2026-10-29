package com.ticketing.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.ticketing.config.OrderProperties;
import com.ticketing.dto.TicketSale;
import com.ticketing.dto.TradeContext;
import com.ticketing.entity.TicketOrder;
import com.ticketing.enums.OrderOperation;
import com.ticketing.exception.BusinessException;
import com.ticketing.mapper.ActivitySessionMapper;
import com.ticketing.mapper.OrderMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 旧内部入口的幂等保护独立于消费者优化路径，包含取消后的历史订单。 */
class OrderReservationTest {
    private final OrderMapper orders = mock(OrderMapper.class);
    private final TicketInventory inventory = mock(TicketInventory.class);
    private final UserSessionQuotaService quotas = mock(UserSessionQuotaService.class);
    private final OrderReservation reservation = new OrderReservation(orders, inventory,
            mock(ActivitySessionMapper.class), new OrderProperties(4, 15, new OrderProperties.Expiry(100)), quotas);
    private final TicketSale sale = mock(TicketSale.class);
    private final TradeContext context = new TradeContext(10, true, false, sale);

    @Test void internalCreateReplaysCancelledOrderWithoutReservingAgain() throws Exception {
        existing(OrderOperation.CREATE_ORDER, 1);
        assertThat(reservation.reservePurchase(context, 1, "historical")).isEqualTo(99);
        verify(orders).selectOne(any(Wrapper.class));
        verify(orders, never()).insertPurchase(any(), anyInt());
        verifyNoInteractions(inventory, quotas);
    }

    @Test void internalCreateStillRejectsAChangedRequestHash() throws Exception {
        existing(OrderOperation.CREATE_ORDER, 1);
        assertThatThrownBy(() -> reservation.reservePurchase(context, 2, "historical"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("IDEMPOTENCY_CONFLICT"));
        verify(orders, never()).insertPurchase(any(), anyInt());
        verifyNoInteractions(inventory, quotas);
    }

    @Test void waitlistReservationKeepsItsOwnIdempotencyCheck() throws Exception {
        existing(OrderOperation.WAITLIST_CONFIRM, 1);
        assertThat(reservation.reservePurchase(context, 1, OrderOperation.WAITLIST_CONFIRM,
                "historical", 123L, false)).isEqualTo(99);
        verify(orders).selectOne(any(Wrapper.class));
        verify(orders, never()).insertPurchase(any(), anyInt());
        verifyNoInteractions(inventory, quotas);
    }

    private void existing(OrderOperation operation, int quantity) throws Exception {
        when(sale.skuId()).thenReturn(20L);
        TicketOrder order = new TicketOrder();
        order.setId(99L);
        order.setStatus("CANCELLED");
        order.setIdempotencyOperation(operation.name());
        order.setRequestHash(MessageDigest.getInstance("SHA-256")
                .digest((20 + ":" + quantity).getBytes(StandardCharsets.UTF_8)));
        when(orders.selectOne(any(Wrapper.class))).thenReturn(order);
    }
}
