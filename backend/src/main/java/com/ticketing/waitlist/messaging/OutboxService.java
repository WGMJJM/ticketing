package com.ticketing.waitlist.messaging;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ticketing.waitlist.entity.OutboxEvent;
import com.ticketing.waitlist.mapper.OutboxMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxService {
    private final OutboxMapper outbox;
    private final int leaseSeconds;
    private final int maxAttempts;
    private final int baseSeconds;
    private final int maxSeconds;
    public OutboxService(OutboxMapper outbox,
            @Value("${ticketing.waitlist.outbox.lease-seconds:30}") int leaseSeconds,
            @Value("${ticketing.waitlist.outbox.max-attempts:10}") int maxAttempts,
            @Value("${ticketing.waitlist.outbox.retry-base-seconds:2}") int baseSeconds,
            @Value("${ticketing.waitlist.outbox.retry-max-seconds:300}") int maxSeconds) {
        if (leaseSeconds<1 || maxAttempts<1 || baseSeconds<1 || maxSeconds<baseSeconds) throw new IllegalArgumentException("Outbox租约及重试配置不正确");
        this.outbox=outbox; this.leaseSeconds=leaseSeconds; this.maxAttempts=maxAttempts; this.baseSeconds=baseSeconds; this.maxSeconds=maxSeconds;
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public OutboxEvent claimNext() {
        OutboxEvent event=outbox.claimCandidate();
        if (event==null) return null;
        if (event.getRetryCount()>=maxAttempts) {
            outbox.update(null,new LambdaUpdateWrapper<OutboxEvent>().eq(OutboxEvent::getId,event.getId())
                    .set(OutboxEvent::getStatus,"FAILED").set(OutboxEvent::getLastError,"投递次数已达上限，请检查消息链路"));
            event.setStatus("FAILED"); return event;
        }
        outbox.claim(event.getId(),leaseSeconds);
        return outbox.selectById(event.getId());
    }

    public boolean sent(OutboxEvent event) { return outbox.sent(event.getId(),event.getRetryCount())==1; }

    public void failed(OutboxEvent event) {
        long delay=baseSeconds;
        for (int index=1; index<event.getRetryCount() && delay<maxSeconds; index++) delay=Math.min((long)maxSeconds,delay*2);
        outbox.failed(event.getId(),event.getRetryCount(),event.getRetryCount()>=maxAttempts?"FAILED":"PENDING",(int)delay,
                "消息未确认或路由失败，请检查消息链路");
    }
}
