package com.ticketing.vo;

import java.time.Instant;

public record AccountSessionView(String id, boolean current, Instant lastActiveAt) {}
