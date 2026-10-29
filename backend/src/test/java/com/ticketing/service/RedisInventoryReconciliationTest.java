package com.ticketing.service;

import com.ticketing.config.PurchaseProperties;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** 只校验扫描读取与比较，不替代下一阶段真实 Lua/数据库一致性验收。 */
class RedisInventoryReconciliationTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final HashOperations<String,Object,Object> hashes = mock(HashOperations.class);
    private final RedisStockReservationService stocks = new RedisStockReservationService(
            redis, new PurchaseProperties("scan_test",200,120,10,100),4);

    @Test
    void sameVersionAndNoPendingReadsLedgerAndBalanceOnce() {
        ledger("4","0","10");
        Supplier<Long> balance = mock(Supplier.class);
        when(balance.get()).thenReturn(10L);
        assertThat(stocks.needsInventoryReconciliation(1,4,balance)).isFalse();
        verify(hashes,times(1)).entries(stocks.skuKey(1));
        verify(balance,times(1)).get();
    }

    @Test
    void balanceMismatchStillRequiresProtectedSynchronization() {
        ledger("4","0","10");
        assertThat(stocks.needsInventoryReconciliation(1,4,()->11L)).isTrue();
    }

    @Test
    void pendingReservationsSkipPrematureBalanceComparison() {
        ledger("4","1","9");
        Supplier<Long> balance = mock(Supplier.class);
        assertThat(stocks.needsInventoryReconciliation(1,4,balance)).isFalse();
        verifyNoInteractions(balance);
    }

    @Test
    void changedVersionSkipsRedundantBalanceRead() {
        ledger("4","0","10");
        Supplier<Long> balance = mock(Supplier.class);
        assertThat(stocks.needsInventoryReconciliation(1,5,balance)).isTrue();
        verifyNoInteractions(balance);
    }

    private void ledger(String version,String pending,String available) {
        Map<Object,Object> fields = new HashMap<>(Map.of(
                "inventoryVersion",version,"pending",pending,"available",available,"ready","1",
                "waitlistPending","0","waitlistPriority","0","eligible","1",
                "saleStartsAt","1","saleEndsAt","2","maxQuantity","4"));
        fields.put("sessionId","1");
        when(redis.<Object,Object>opsForHash()).thenReturn(hashes);
        when(hashes.entries(stocks.skuKey(1))).thenReturn(fields);
    }
}
