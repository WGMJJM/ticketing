package com.ticketing.admission;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ticketing.auth.UserAccount;
import com.ticketing.auth.UserAccountMapper;
import com.ticketing.messaging.PurchaseCommands;
import java.time.LocalDateTime;
import java.util.Set;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 交易只通过此协作边界读写受理状态；受理模块不反向依赖订单实现。 */
@Service
public class PurchaseProcessing {
    private static final Set<String> BUSINESS_FAILURES=Set.of("TICKET_UNAVAILABLE","STOCK_UNAVAILABLE","TICKET_NOT_FOUND","SESSION_NOT_FOUND","PURCHASE_LIMIT","ACCOUNT_UNAVAILABLE","ORDER_AMOUNT_INVALID");
    private final PurchaseRequestMapper requests;
    private final UserAccountMapper users;
    private final PurchaseCommands commands;
    private final PurchaseProperties properties;
    private final MeterRegistry meters;
    public PurchaseProcessing(PurchaseRequestMapper requests,UserAccountMapper users,PurchaseCommands commands,PurchaseProperties properties,MeterRegistry meters) {
        this.requests=requests; this.users=users; this.commands=commands; this.properties=properties;
        this.meters=meters;
    }
    public PurchaseRequest find(long id) { return requests.find(id); }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public PurchaseRequest claim(long id) {
        if(requests.claim(id,properties.leaseSeconds())!=1) {
            PurchaseRequest current=requests.find(id);
            meters.counter("ticketing.purchase.delivery","outcome",current!=null && current.terminal()?"terminal-replay":"not-claimable").increment();
            return null;
        }
        PurchaseRequest claimed=requests.find(id);
        // 两个时刻均来自MySQL；租约截止减配置时长等于本次认领SQL的数据库时间。
        Duration delay=Duration.between(claimed.acceptedAt(),claimed.processingLeaseUntil().minusSeconds(properties.leaseSeconds()));
        Timer.builder("ticketing.purchase.accepted.to.claim").publishPercentiles(.95,.99).register(meters).record(delay);
        return claimed;
    }

    /** 调用方先获取用户和交易父资源；持请求行直到交易提交，最终CAS再次核验数据库时间。 */
    @Transactional(propagation=Propagation.MANDATORY)
    public boolean lockValid(PurchaseRequest claim) {
        PurchaseRequest current=requests.lock(claim.id());
        LocalDateTime now=requests.now();
        return current!=null && "PROCESSING".equals(current.status()) && current.processingToken()==claim.processingToken()
                && current.deadlineAt().isAfter(now) && current.processingLeaseUntil().isAfter(now);
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public void succeed(PurchaseRequest claim,long orderId) {
        if(requests.finish(claim.id(),claim.processingToken(),"SUCCEEDED",orderId,null)!=1) throw new IllegalStateException("购票处理资格或截止时间已失效，回滚订单");
        release();
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public void fail(PurchaseRequest claim,String code) {
        if(!BUSINESS_FAILURES.contains(code)) throw new IllegalArgumentException("不可将临时故障记为业务失败");
        lockUser(claim.userId());
        if(!lockCurrent(claim)) return;
        if(requests.expire(claim.id())==1) { release(); commands.close(claim.id()); return; }
        if(requests.finish(claim.id(),claim.processingToken(),"FAILED",null,code)==1) release();
    }
    public boolean deterministic(String code) { return BUSINESS_FAILURES.contains(code); }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public void recover(long id) {
        PurchaseRequest initial=requests.find(id);
        if(initial==null || initial.terminal()) return;
        lockUser(initial.userId());
        PurchaseRequest current=requests.lock(id);
        if(current==null || current.terminal()) return;
        if(requests.expire(id)==1) { release(); commands.close(id); return; }
        if(commands.failed(id)) {
            if(requests.failDeliveryExhausted(id)==1) {
                release();
                meters.counter("ticketing.purchase.recovery","outcome","delivery-exhausted").increment();
                return;
            }
            if(requests.expire(id)==1) { release(); commands.close(id); return; }
        }
        if(requests.scheduleRecovery(id,properties.recoverySeconds())==1) commands.recover(id);
    }
    private boolean lockCurrent(PurchaseRequest claim) {
        PurchaseRequest current=requests.lock(claim.id());
        return current!=null && "PROCESSING".equals(current.status()) && current.processingToken()==claim.processingToken();
    }
    private void lockUser(long userId) { users.selectOne(new LambdaQueryWrapper<UserAccount>().eq(UserAccount::getId,userId).last("FOR UPDATE")); }
    private void release() { if(requests.release()!=1) throw new IllegalStateException("购票受理预算不一致，回滚终态"); }
}
