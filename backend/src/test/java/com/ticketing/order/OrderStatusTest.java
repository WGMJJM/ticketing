package com.ticketing.order;

import com.ticketing.order.model.OrderStatus;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class OrderStatusTest {
    @Test
    void onlyPendingMayEnterOneTerminalState() {
        assertThat(OrderStatus.PENDING.canTransitionTo(null)).isFalse();
        for (OrderStatus source : OrderStatus.values()) {
            for (OrderStatus target : OrderStatus.values()) {
                assertThat(source.canTransitionTo(target)).isEqualTo(source == OrderStatus.PENDING && target != OrderStatus.PENDING);
            }
        }
    }
}
