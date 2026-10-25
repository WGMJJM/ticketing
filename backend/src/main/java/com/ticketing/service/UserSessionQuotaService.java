package com.ticketing.service;

import com.ticketing.config.OrderProperties;
import com.ticketing.exception.BusinessException;
import com.ticketing.mapper.UserSessionQuotaMapper;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 额度写入必须在父资源之后、票档之前先lock；额度变化与订单和库存属于同一事务。 */
@Service
public class UserSessionQuotaService {
    private final UserSessionQuotaMapper mapper;
    private final OrderProperties properties;
    private final MeterRegistry meters;
    public UserSessionQuotaService(UserSessionQuotaMapper mapper,OrderProperties properties,MeterRegistry meters) {
        this.mapper=mapper;this.properties=properties;this.meters=meters;
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void lock(long user,long session) {
        long start=System.nanoTime();
        try { mapper.lock(user,session); }
        finally { meters.timer("ticketing.trade.lock.duration","resource","quota").record(System.nanoTime()-start,TimeUnit.NANOSECONDS); }
    }
    @Transactional(readOnly=true)
    public int occupied(long user,long session) {
        Integer value=mapper.occupied(user,session);
        return value==null?0:value;
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void occupy(long user,long session,int quantity) {
        if(mapper.occupy(user,session,quantity,properties.maxPerUserSession())!=1) {
            if(mapper.occupied(user,session)==null) throw new IllegalStateException("用户场次额度行缺失，回滚交易并核对锁协议");
            meters.counter("ticketing.trade.quota","operation","rejected").increment();
            throw new BusinessException(HttpStatus.CONFLICT,"PURCHASE_LIMIT",
                    "同一用户每场最多占有"+properties.maxPerUserSession()+"张，请先取消不需要的待支付订单");
        }
        meters.counter("ticketing.trade.quota","operation","occupied").increment();
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void release(long user,long session,int quantity) {
        if(mapper.release(user,session,quantity)!=1) {
            meters.counter("ticketing.trade.quota","operation","inconsistent").increment();
            throw new IllegalStateException("用户场次额度不足以释放，回滚交易并人工核对");
        }
        meters.counter("ticketing.trade.quota","operation","released").increment();
    }
}
