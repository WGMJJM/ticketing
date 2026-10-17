package com.ticketing.ticket.service;

import com.ticketing.event.entity.Activity;
import com.ticketing.event.entity.ActivitySession;
import com.ticketing.ticket.entity.TicketSku;
import com.ticketing.ticket.model.SaleStatus;
import java.time.LocalDateTime;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;

class TicketSaleStatusTest {
    private static final LocalDateTime OPEN = LocalDateTime.parse("2030-05-01T10:00:00");

    static Stream<Arguments> cases() {
        return Stream.of(
                Arguments.of("PUBLISHED", "SCHEDULED", "ON_SALE", -1, 10, 0, 0, SaleStatus.NOT_STARTED),
                Arguments.of("PUBLISHED", "SCHEDULED", "ON_SALE", 0, 10, 2, 3, SaleStatus.SELLING),
                Arguments.of("PUBLISHED", "SCHEDULED", "ON_SALE", 30, 5, 2, 3, SaleStatus.SOLD_OUT),
                Arguments.of("PUBLISHED", "SCHEDULED", "ON_SALE", 30, 0, 0, 0, SaleStatus.SOLD_OUT),
                Arguments.of("PUBLISHED", "SCHEDULED", "ON_SALE", 59, 10, 0, 0, SaleStatus.SELLING),
                Arguments.of("PUBLISHED", "SCHEDULED", "ON_SALE", 60, 10, 0, 0, SaleStatus.STOPPED),
                Arguments.of("PUBLISHED", "SCHEDULED", "ON_SALE", 90, 0, 0, 0, SaleStatus.STOPPED),
                Arguments.of("DRAFT", "SCHEDULED", "ON_SALE", 30, 10, 0, 0, SaleStatus.UNAVAILABLE),
                Arguments.of("OFFLINE", "SCHEDULED", "ON_SALE", 30, 10, 0, 0, SaleStatus.UNAVAILABLE),
                Arguments.of("PUBLISHED", "CANCELLED", "ON_SALE", 30, 10, 0, 0, SaleStatus.UNAVAILABLE),
                Arguments.of("PUBLISHED", "ENDED", "ON_SALE", 30, 10, 0, 0, SaleStatus.UNAVAILABLE),
                Arguments.of("PUBLISHED", "SCHEDULED", "DRAFT", 30, 10, 0, 0, SaleStatus.UNAVAILABLE),
                Arguments.of("PUBLISHED", "SCHEDULED", "OFF_SALE", 30, 10, 0, 0, SaleStatus.UNAVAILABLE),
                Arguments.of("PUBLISHED", "SCHEDULED", "ON_SALE", 120, 10, 0, 0, SaleStatus.UNAVAILABLE));
    }

    @ParameterizedTest
    @MethodSource("cases")
    void derivesStatusFromParentsManagementWindowAndAuthoritativeStock(String activityState, String sessionState,
            String ticketState, int minutes, int total, int locked, int sold, SaleStatus expected) {
        Activity activity = new Activity();
        activity.setStatus(activityState);
        ActivitySession session = new ActivitySession();
        session.setStatus(sessionState);
        session.setSaleStartsAt(OPEN.minusDays(1));
        session.setStartsAt(OPEN.plusMinutes(60));
        session.setEndsAt(OPEN.plusMinutes(120));
        TicketSku sku = new TicketSku();
        sku.setStatus(ticketState);
        sku.setSaleStartsAt(OPEN);
        sku.setSaleEndsAt(OPEN.plusMinutes(60));
        sku.setTotalStock(total);
        sku.setLockedStock(locked);
        sku.setSoldStock(sold);
        assertThat(TicketService.saleStatus(activity, session, sku, OPEN.plusMinutes(minutes))).isEqualTo(expected);
    }

    @Test
    void legacyWindowConflictingWithParentFailsClosed() {
        Activity activity = new Activity();
        activity.setStatus("PUBLISHED");
        ActivitySession session = new ActivitySession();
        session.setStatus("SCHEDULED");
        session.setSaleStartsAt(OPEN.plusMinutes(10));
        session.setStartsAt(OPEN.plusHours(1));
        session.setEndsAt(OPEN.plusHours(2));
        TicketSku sku = new TicketSku();
        sku.setStatus("ON_SALE");
        sku.setSaleStartsAt(OPEN);
        sku.setSaleEndsAt(OPEN.plusHours(1));
        assertThat(TicketService.saleStatus(activity, session, sku, OPEN)).isEqualTo(SaleStatus.UNAVAILABLE);
        session.setSaleStartsAt(OPEN);
        sku.setSaleEndsAt(OPEN.plusHours(2));
        assertThat(TicketService.saleStatus(activity, session, sku, OPEN)).isEqualTo(SaleStatus.UNAVAILABLE);
    }
}
