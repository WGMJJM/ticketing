package com.ticketing;

import com.ticketing.cache.CatalogCache;
import com.ticketing.dto.ActivityQuery;
import com.ticketing.dto.ActivityWriteRequest;
import com.ticketing.dto.AdminCredentials;
import com.ticketing.dto.SessionWriteRequest;
import com.ticketing.dto.TicketWriteRequest;
import com.ticketing.dto.VersionRequest;
import com.ticketing.enums.ActivityCategory;
import com.ticketing.enums.ActivityStatus;
import com.ticketing.enums.SessionStatus;
import com.ticketing.exception.BusinessException;
import com.ticketing.service.AccountService;
import com.ticketing.service.AccountUserDetailsService;
import com.ticketing.service.ActivityService;
import com.ticketing.service.SessionService;
import com.ticketing.service.TicketService;

import com.fasterxml.jackson.core.type.TypeReference;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

/** 真实MySQL、Redis及提交回调，不使用事务测试回滚替代写后失效。 */
@SpringBootTest(properties = {"ticketing.catalog-cache.enabled=true", "ticketing.catalog-cache.local-seconds=1",
        "ticketing.catalog-cache.negative-seconds=1", "ticketing.catalog-cache.maximum-entries=16",
        "ticketing.catalog-cache.concurrent-loads=2", "ticketing.catalog-cache.load-wait-ms=40",
        "ticketing.catalog-cache.namespace=stage15_it"})
class CatalogCacheIT {
    @Autowired CatalogCache cache;
    @Autowired ActivityService activities;
    @Autowired SessionService sessions;
    @Autowired TicketService tickets;
    @Autowired AccountService accounts;
    @Autowired AccountUserDetailsService identities;
    @Autowired JdbcTemplate db;
    @Autowired StringRedisTemplate redis;
    @Autowired MeterRegistry meters;
    @Autowired PlatformTransactionManager manager;
    long activity;
    TransactionTemplate transaction;

    @BeforeEach void fixture() {
        transaction = new TransactionTemplate(manager);
        String username = "cache_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        accounts.initializeAdmin(new AdminCredentials(username, UUID.randomUUID().toString()));
        var principal = identities.loadUserByUsername(username);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        activity = Long.parseLong(activities.create(request("缓存验收", null)).id());
        activities.publish(activity, 0);
    }
    @AfterEach void security() { SecurityContextHolder.clearContext(); }
    ActivityWriteRequest request(String title, Integer version) { return new ActivityWriteRequest(title, ActivityCategory.CONCERT, "上海", "隔离场馆", "简介", "正文", null, version); }
    double count(String kind, String outcome) {
        var counter = meters.find("ticketing.catalog.requests").tags("kind", kind, "outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }
    @Test void realTwoLevelHitsNegativeCacheAndAuthoritativeBypass() throws Exception {
        double dbBefore = count("detail", "db"), l2Before = count("detail", "l2");
        assertThat(activities.detail(activity, true).title()).isEqualTo("缓存验收");
        activities.detail(activity, true);
        assertThat(count("detail", "db") - dbBefore).isEqualTo(1);
        assertThat(count("detail", "l1")).isPositive();
        Thread.sleep(1100);
        activities.detail(activity, true);
        assertThat(count("detail", "l2") - l2Before).isEqualTo(1);
        assertThat(count("detail", "db") - dbBefore).isEqualTo(1);
        activities.detail(activity, false);
        transaction.executeWithoutResult(status -> activities.detail(activity, true));
        assertThat(count("detail", "db") - dbBefore).isEqualTo(1);
        long missing = Long.MAX_VALUE;
        double negativeBefore = count("detail", "db");
        for (int i = 0; i < 2; i++) assertThatThrownBy(() -> activities.detail(missing, true)).isInstanceOf(BusinessException.class);
        assertThat(count("detail", "db") - negativeBefore).isEqualTo(1);
        Thread.sleep(1100);
        assertThatThrownBy(() -> activities.detail(missing, true)).isInstanceOf(BusinessException.class);
        assertThat(count("detail", "db") - negativeBefore).isEqualTo(2);
    }
    @Test void publicationOfflineRollbackAndSessionChangesInvalidateAfterCommit() {
        activities.detail(activity, true);
        double before = count("detail", "db");
        transaction.executeWithoutResult(status -> { activities.offline(activity, 1); status.setRollbackOnly(); });
        assertThat(activities.detail(activity, true).status()).isEqualTo(ActivityStatus.PUBLISHED);
        assertThat(count("detail", "db")).isEqualTo(before);
        activities.offline(activity, 1);
        assertThatThrownBy(() -> activities.detail(activity, true)).isInstanceOf(BusinessException.class);
        activities.update(activity, request("新版", 2));
        activities.publish(activity, 3);
        assertThat(activities.detail(activity, true).title()).isEqualTo("新版");
        assertThat(sessions.list(activity, true)).isEmpty();
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        var created = sessions.create(activity, new SessionWriteRequest("新场次", now.plusDays(1), now.plusDays(2), now.minusHours(1), null));
        long session = Long.parseLong(created.id());
        assertThat(sessions.list(activity, true)).hasSize(1);
        var returned = sessions.list(activity, true);
        returned.clear();
        assertThat(sessions.list(activity, true)).hasSize(1);
        sessions.update(session, new SessionWriteRequest("已修改", now.plusDays(1), now.plusDays(2), now.minusHours(1), 0));
        assertThat(sessions.list(activity, true).getFirst().title()).isEqualTo("已修改");
        sessions.cancel(session, 1);
        assertThat(sessions.list(activity, true).getFirst().status()).isEqualTo(SessionStatus.CANCELLED);
    }
    @Test void finiteListKeysSeparateDimensionsAndDoNotCacheKeywordOrUnboundedFilters() {
        assertThat(cache.cacheList(new ActivityQuery(null, null, "上海", null, 1, 12))).isTrue();
        for (var query : List.of(new ActivityQuery("搜索", null, null, null, 1, 12),
                new ActivityQuery(null, null, "任意城市", null, 1, 12), new ActivityQuery(null, null, null, null, 4, 12),
                new ActivityQuery(null, null, null, null, 1, 7))) assertThat(cache.cacheList(query)).isFalse();
        double before = count("list", "db");
        for (String city : List.of("上海", "北京")) for (int page : List.of(1, 2)) for (int size : List.of(12, 20)) {
            var query = new ActivityQuery(null, ActivityCategory.CONCERT, city, null, page, size);
            var first = activities.list(query, true);
            assertThat(activities.list(query, true)).isEqualTo(first);
            assertThat(first.items()).allMatch(value -> value.city().equals(city));
        }
        assertThat(count("list", "db") - before).isEqualTo(8);
        var query = new ActivityQuery(null, ActivityCategory.EXHIBITION, null, null, 1, 12);
        activities.list(query, true);
        assertThat(count("list", "db") - before).isEqualTo(9);
        activities.list(query, false);
        assertThat(count("list", "db") - before).isEqualTo(9);
    }
    @Test void coldKeyCoalescesAndLateOldFillCannotSurviveCommittedInvalidation() throws Exception {
        AtomicInteger loaded = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        String key = UUID.randomUUID().toString();
        try (var pool = Executors.newFixedThreadPool(20)) {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < 20; i++) futures.add(pool.submit(() -> cache.read(CatalogCache.Kind.DETAIL, key,
                    new TypeReference<String>() {}, () -> { loaded.incrementAndGet(); started.countDown(); await(release); return "旧值"; })));
            assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(100);
            assertThat(loaded).hasValue(1);
            transaction.executeWithoutResult(status -> cache.invalidateAfterCommit());
            release.countDown();
            for (var future : futures) future.get(5, TimeUnit.SECONDS);
        } finally { release.countDown(); }
        assertThat(cache.read(CatalogCache.Kind.DETAIL, key, new TypeReference<String>() {}, () -> "新值")).isEqualTo("新值");
    }
    @Test void differentColdKeysHaveBoundedDatabaseConcurrency() throws Exception {
        CountDownLatch entered = new CountDownLatch(2), release = new CountDownLatch(1);
        String keyPrefix = UUID.randomUUID().toString();
        try (var pool = Executors.newFixedThreadPool(3)) {
            List<Future<String>> active = new ArrayList<>();
            for (String suffix : List.of("A", "B")) active.add(pool.submit(() -> cache.read(CatalogCache.Kind.DETAIL, keyPrefix + suffix,
                    new TypeReference<String>() {}, () -> { entered.countDown(); await(release); return "值"; })));
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> cache.read(CatalogCache.Kind.DETAIL, keyPrefix + "C", new TypeReference<String>() {}, () -> "不应加载"))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("繁忙");
            release.countDown();
            for (var future : active) future.get(3, TimeUnit.SECONDS);
        } finally { release.countDown(); }
    }
    @Test void ticketInventoryAndClockStayLiveAndTicketManagementInvalidatesCatalog() throws Exception {
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        long session = Long.parseLong(sessions.create(activity, new SessionWriteRequest("实时场次", now.plusDays(1), now.plusDays(2), now.minusDays(1), null)).id());
        activities.detail(activity, true);
        double before = count("detail", "db");
        var sku = tickets.create(session, new TicketWriteRequest("实时票档", "100", 20, now.minusHours(1), now.plusHours(1), 4, null));
        long id = Long.parseLong(sku.id());
        tickets.publish(id, new VersionRequest(0));
        activities.detail(activity, true);
        assertThat(count("detail", "db")).isGreaterThan(before);
        var original = tickets.publicList(activity, session).getFirst();
        db.update("UPDATE ticket_sku SET locked_stock=2 WHERE id=?", id);
        Thread.sleep(5);
        var changed = tickets.publicList(activity, session).getFirst();
        assertThat(changed.availableStock()).isEqualTo(original.availableStock() - 2);
        assertThat(changed.serverTime()).isAfter(original.serverTime());
        tickets.offline(id, new VersionRequest(1));
        assertThat(tickets.publicList(activity, session)).isEmpty();
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("测试等待超时"); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
    }
}
