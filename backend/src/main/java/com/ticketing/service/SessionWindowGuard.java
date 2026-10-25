package com.ticketing.service;

import java.time.LocalDateTime;

/** 场次UPDATE锁内执行子资源窗口校验；实现不得反向取得用户或父级锁。 */
public interface SessionWindowGuard {
    void validate(long sessionId, LocalDateTime saleStartsAt, LocalDateTime startsAt);
}
