package com.ticketing.service;

import com.ticketing.mapper.InventoryVersionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 库存变更与版本递增同事务；独立版本行不升级分桶 SKU 的共享锁。 */
@Service
public class InventoryVersionService {
    private final InventoryVersionMapper versions;

    public InventoryVersionService(InventoryVersionMapper versions) {
        this.versions = versions;
    }

    public long current(long skuId) {
        return versions.current(skuId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void changed(long skuId) {
        if (versions.increment(skuId) < 1) throw new IllegalStateException("库存同步版本未更新");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void activityChanged(long activityId) {
        versions.forActivity(activityId).forEach(this::changed);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void sessionChanged(long sessionId) {
        versions.forSession(sessionId).forEach(this::changed);
    }
}
