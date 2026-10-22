package com.ticketing.order.dto;

import java.time.Instant;

public record AdminOrderView(OrderView order, String userId, String username, Instant paidAt) {}
