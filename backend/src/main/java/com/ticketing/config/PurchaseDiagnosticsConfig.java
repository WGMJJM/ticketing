package com.ticketing.config;

import com.zaxxer.hikari.HikariDataSource;
import com.ticketing.entity.PurchaseRequest;
import com.ticketing.common.RedisCommandDiagnostics;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.sql.DataSource;
import org.aopalliance.intercept.MethodInterceptor;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 仅隔离 JMeter 环境显式启用；不修改 SQL、锁序、事务传播或默认业务配置。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "ticketing.diagnostics.enabled", havingValue = "true")
public class PurchaseDiagnosticsConfig {
    private static final ThreadLocal<String> ROLE = new ThreadLocal<>();
    private static final String PREFIX = "ticketing.diagnostics.";

    @Bean
    CompletionLog purchaseCompletionLog() { return new CompletionLog(); }

    @Bean
    RedisCommandDiagnostics purchaseRedisCommandDiagnostics(MeterRegistry registry) {
        return new RedisCommandDiagnostics(registry);
    }

    /** 仅保存最近512条无凭据终态时间，用于测试线程等待完整建单，不轮询业务数据库。 */
    static class CompletionLog {
        private final Map<String, Map<String, Object>> recent = new LinkedHashMap<>();
        private long count;

        synchronized void add(PurchaseRequest request, long completedMs) {
            String id = Long.toString(request.id());
            if (!request.terminal() || recent.containsKey(id)) return;
            recent.put(id, Map.of("requestId", id, "status", request.status(), "orderId",
                    request.orderId() == null ? "" : request.orderId().toString(), "completedMs", completedMs));
            count++;
            if (recent.size() > 512) recent.remove(recent.keySet().iterator().next());
        }

        synchronized Map<String, Object> snapshot() {
            return Map.of("count", count, "recent", new LinkedHashMap<>(recent));
        }
    }

    @Bean
    Object purchaseDiagnosticsIsolation(Environment environment) {
        validateIsolation(environment);
        return new Object();
    }

    static void validateIsolation(Environment environment) {
        String url = environment.getRequiredProperty("spring.datasource.url");
        if (!url.startsWith("jdbc:mysql://127.0.0.1:13307/ticketingjmeter?")
                || !"127.0.0.1".equals(environment.getProperty("server.address"))
                || !"18080".equals(environment.getProperty("server.port"))
                || !"127.0.0.1".equals(environment.getProperty("management.server.address"))
                || !"18081".equals(environment.getProperty("management.server.port"))) {
            throw new IllegalStateException("购票诊断仅允许 ticketingjmeter 隔离库与本机 18080/18081");
        }
    }

    @Bean
    @Order(-1)
    SecurityFilterChain purchaseDiagnosticsSecurity(HttpSecurity http, Environment environment) throws Exception {
        return http.securityMatcher(request -> request.getLocalPort() == 18081
                        && List.of("/actuator/purchaseDiagnostics", "/actuator/shutdown").contains(request.getRequestURI())
                        && "127.0.0.1".equals(request.getRemoteAddr()))
                .csrf(csrf -> csrf.ignoringRequestMatchers(request -> authorizedShutdown(request, environment)))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/actuator/purchaseDiagnostics").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/actuator/shutdown")
                        .access((authentication, context) -> new org.springframework.security.authorization.AuthorizationDecision(
                                authorizedShutdown(context.getRequest(), environment)))
                        .anyRequest().denyAll())
                .build();
    }

    static boolean authorizedShutdown(jakarta.servlet.http.HttpServletRequest request, Environment environment) {
        String expected = environment.getProperty("ticketing.diagnostics.shutdown-token", "");
        String actual = request.getHeader("X-Ticketing-Diagnostics-Stop");
        return expected.length() >= 32 && actual != null && request.getLocalPort() == 18081
                && "127.0.0.1".equals(request.getRemoteAddr()) && "POST".equals(request.getMethod())
                && "/actuator/shutdown".equals(request.getRequestURI())
                && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    static String role() {
        if (ROLE.get() != null) return ROLE.get();
        String name = Thread.currentThread().getName();
        if (name.contains("http") || name.contains("tomcat")) return "http";
        if (name.contains("scheduling")) return "task";
        return "other";
    }

    static Timer timer(MeterRegistry registry, String stage, String role) {
        return Timer.builder(PREFIX + "duration").tags("stage", stage, "role", role)
                .publishPercentiles(0.5, 0.95, 0.99).register(registry);
    }

    @Bean
    Interceptor purchaseSqlDiagnostics(MeterRegistry registry) {
        return new SqlDiagnostics(registry);
    }

    @Intercepts({
        @Signature(type = Executor.class, method = "query", args = {MappedStatement.class, Object.class,
                RowBounds.class, ResultHandler.class}),
        @Signature(type = Executor.class, method = "query", args = {MappedStatement.class, Object.class,
                RowBounds.class, ResultHandler.class, CacheKey.class, BoundSql.class}),
        @Signature(type = Executor.class, method = "queryCursor", args = {MappedStatement.class, Object.class,
                RowBounds.class}),
        @Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class})
    })
    static class SqlDiagnostics implements Interceptor {
        private final MeterRegistry registry;

        SqlDiagnostics(MeterRegistry registry) { this.registry = registry; }

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            MappedStatement statement = (MappedStatement) invocation.getArgs()[0];
            String id = statement.getId().replace("com.ticketing.mapper.", "");
            String sql = statement.getBoundSql(invocation.getArgs()[1]).getSql().toUpperCase(Locale.ROOT);
            long start = System.nanoTime();
            boolean success = false;
            Object result;
            try {
                result = invocation.proceed();
                success = true;
            } finally {
                timer(registry, "mapper." + id, role()).record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
                if (!success) registry.counter(PREFIX + "mapper.errors", "call", id, "role", role()).increment();
            }
            // SELECT 往返结束到 afterCompletion 是客户端观测持有区间，非服务器精确解锁时间。
            boolean rowFound = (result instanceof List<?> rows && !rows.isEmpty())
                    || result instanceof org.apache.ibatis.cursor.Cursor<?>;
            String heldStage = null;
            if (sql.contains("FROM TICKET_SKU ") && rowFound) {
                if (sql.contains("FOR SHARE")) heldStage = "sku.shared.held.client";
                else if (sql.contains("FOR UPDATE")) heldStage = "stock.held.client";
            } else if ((sql.contains("UPDATE TICKET_STOCK_BUCKET ") && result instanceof Integer changed && changed > 0)
                    || (sql.contains("FROM TICKET_STOCK_BUCKET ") && sql.contains("FOR UPDATE") && rowFound)) {
                heldStage = "bucket.held.client";
            }
            // 每事务只记录首次实际桶锁返回至提交/回滚，不能把配置行 SHARE 锁当作库存 X 锁。
            if (heldStage != null && TransactionSynchronizationManager.isSynchronizationActive()) {
                String stage = heldStage;
                if (TransactionSynchronizationManager.getSynchronizations().stream()
                        .noneMatch(sync -> sync instanceof StockHold hold && hold.stage.equals(stage))) {
                    TransactionSynchronizationManager.registerSynchronization(new StockHold(registry, role(), stage));
                }
            }
            return result;
        }
    }

    static class StockHold implements TransactionSynchronization {
        private final MeterRegistry registry;
        private final String role;
        private final String stage;
        private final long acquired = System.nanoTime();

        StockHold(MeterRegistry registry, String role) { this(registry, role, "stock.held.client"); }

        StockHold(MeterRegistry registry, String role, String stage) {
            this.registry = registry; this.role = role; this.stage = stage;
        }

        @Override
        public void afterCompletion(int status) {
            timer(registry, stage, role).record(System.nanoTime() - acquired, TimeUnit.NANOSECONDS);
            registry.counter(PREFIX + "stock.completions", "role", role, "stage", stage,
                    "outcome", status == STATUS_COMMITTED ? "committed" : "rolled_back").increment();
        }
    }

    @Bean
    static BeanPostProcessor purchaseStageDiagnostics(ObjectProvider<MeterRegistry> registries, Environment environment,
            ObjectProvider<CompletionLog> completions) {
        // BeanPostProcessor先于数据源/迁移初始化，错误环境必须在任何业务SQL前拒绝。
        validateIsolation(environment);
        return new BeanPostProcessor() {
            private final ThreadLocal<Map<TransactionStatus, Long>> starts = ThreadLocal.withInitial(IdentityHashMap::new);

            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (!List.of("transactionManager", "purchaseOrderWorker", "redisStockReservationService",
                        "orderReservation", "purchasePublisher", "stockSnapshotService").contains(name)) return bean;
                MethodInterceptor advice = invocation -> {
                    String method = invocation.getMethod().getName();
                    String stage = switch (name) {
                        case "transactionManager" -> List.of("getTransaction", "commit", "rollback").contains(method)
                                ? "transaction." + method : null;
                        case "purchaseOrderWorker" -> List.of("process", "recover").contains(method) ? "worker." + method : null;
                        case "redisStockReservationService" -> List.of("find", "require", "settle", "reserve").contains(method)
                                ? "redis." + method : null;
                        case "orderReservation" -> method.equals("reservePurchase") ? "reservation" : null;
                        case "purchasePublisher" -> method.equals("publish") ? "publisher.send" : null;
                        case "stockSnapshotService" -> method.equals("synchronize") ? "inventory.synchronize" : null;
                        default -> null;
                    };
                    if (stage == null) return invocation.proceed();
                    String previous = ROLE.get();
                    if (name.equals("purchaseOrderWorker")) ROLE.set(method.equals("process") ? "consumer" : "recovery");
                    long start = System.nanoTime();
                    boolean realTransaction = !name.equals("transactionManager") || method.equals("getTransaction")
                            || ((TransactionStatus) invocation.getArguments()[0]).isNewTransaction();
                    try {
                        Object result = invocation.proceed();
                        if (name.equals("redisStockReservationService") && method.equals("settle") && Boolean.TRUE.equals(result)) {
                            completions.getObject().add((PurchaseRequest) invocation.getArguments()[0], System.currentTimeMillis());
                        }
                        if (name.equals("transactionManager") && method.equals("getTransaction")) {
                            realTransaction = ((TransactionStatus) result).isNewTransaction();
                            if (realTransaction) starts.get().put((TransactionStatus) result, start);
                        }
                        if (name.equals("stockSnapshotService") && method.equals("synchronize")) {
                            registries.getObject().counter(PREFIX + "inventory.synchronize", "result",
                                    Boolean.TRUE.equals(result) ? "published" : "waiting_or_missing").increment();
                        }
                        return result;
                    } finally {
                        MeterRegistry registry = registries.getObject();
                        long end = System.nanoTime();
                        if (realTransaction) timer(registry, stage, role()).record(end - start, TimeUnit.NANOSECONDS);
                        if (name.equals("transactionManager") && !method.equals("getTransaction") && realTransaction) {
                            Long began = starts.get().remove((TransactionStatus) invocation.getArguments()[0]);
                            if (began != null) timer(registry, "transaction.total", role()).record(end - began, TimeUnit.NANOSECONDS);
                            if (starts.get().isEmpty()) starts.remove();
                        }
                        if (previous == null) ROLE.remove(); else ROLE.set(previous);
                    }
                };
                if (bean instanceof Advised advised) { advised.addAdvice(0, advice); return bean; }
                ProxyFactory factory = new ProxyFactory(bean);
                // AbstractPlatformTransactionManager的入口为final，必须委托接口代理。
                factory.setProxyTargetClass(!name.equals("transactionManager"));
                factory.addAdvice(advice);
                return factory.getProxy();
            }
        };
    }

    @Bean
    DiagnosticsEndpoint purchaseDiagnosticsEndpoint(MeterRegistry registry, DataSource source,
            RabbitListenerEndpointRegistry listeners, Environment environment, CompletionLog completions,
            RedisCommandDiagnostics redisCommands) {
        return new DiagnosticsEndpoint(registry, source, listeners, environment, completions, redisCommands);
    }

    @Endpoint(id = "purchaseDiagnostics")
    static class DiagnosticsEndpoint {
        private final MeterRegistry registry;
        private final DataSource source;
        private final RabbitListenerEndpointRegistry listeners;
        private final Environment environment;
        private final CompletionLog completions;
        private final RedisCommandDiagnostics redisCommands;

        DiagnosticsEndpoint(MeterRegistry registry, DataSource source, RabbitListenerEndpointRegistry listeners,
                Environment environment, CompletionLog completions, RedisCommandDiagnostics redisCommands) {
            this.registry = registry; this.source = source; this.listeners = listeners; this.environment = environment;
            this.completions = completions; this.redisCommands = redisCommands;
        }

        @ReadOperation
        public Map<String, Object> snapshot() throws java.sql.SQLException {
            HikariDataSource pool = source.unwrap(HikariDataSource.class);
            var bean = pool.getHikariPoolMXBean();
            var consumer = (SimpleMessageListenerContainer) listeners.getListenerContainer("purchaseOrders");
            List<Map<String, Object>> metrics = new ArrayList<>();
            for (Meter meter : registry.getMeters()) {
                String name = meter.getId().getName();
                if (!(name.startsWith(PREFIX) || name.startsWith("hikaricp.")
                        || name.startsWith("lettuce.command.")
                        || name.equals("ticketing.trade.lock.duration") || name.equals("jvm.gc.pause"))) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                Map<String, String> tags = new LinkedHashMap<>();
                meter.getId().getTags().forEach(tag -> tags.put(tag.getKey(), tag.getValue()));
                row.put("name", name); row.put("tags", tags);
                if (meter instanceof Timer timer) {
                    row.put("count", timer.count()); row.put("sumMs", timer.totalTime(TimeUnit.MILLISECONDS));
                    row.put("maxMs", timer.max(TimeUnit.MILLISECONDS));
                    Map<String, Double> percentiles = new LinkedHashMap<>();
                    for (var value : timer.takeSnapshot().percentileValues()) {
                        percentiles.put(Double.toString(value.percentile()), value.value(TimeUnit.MILLISECONDS));
                    }
                    row.put("percentilesMs", percentiles);
                } else {
                    Map<String, Double> measurements = new LinkedHashMap<>();
                    meter.measure().forEach(m -> measurements.put(m.getStatistic().name(), m.getValue()));
                    row.put("measurements", measurements);
                }
                metrics.add(row);
            }
            var heap = java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
            return Map.ofEntries(
                    Map.entry("timeMs", System.currentTimeMillis()), Map.entry("pid", ProcessHandle.current().pid()),
                    Map.entry("heap", Map.of("usedBytes", heap.getUsed(), "maxBytes", heap.getMax())),
                    Map.entry("redisTimeouts", Map.of("command", environment.getProperty("spring.data.redis.timeout", "not-set"),
                            "connect", environment.getProperty("spring.data.redis.connect-timeout", "not-set"))),
                    Map.entry("lettuceMeters", registry.getMeters().stream().map(meter -> meter.getId().getName())
                            .filter(name -> name.startsWith("lettuce") || name.startsWith("redis.client"))
                            .distinct().toList()),
                    Map.entry("pool", Map.of("maximum", pool.getMaximumPoolSize(), "timeoutMs", pool.getConnectionTimeout(),
                            "active", bean.getActiveConnections(), "idle", bean.getIdleConnections(),
                            "pending", bean.getThreadsAwaitingConnection(), "total", bean.getTotalConnections())),
                    Map.entry("consumer", Map.of("configured", new org.springframework.beans.DirectFieldAccessor(consumer).getPropertyValue("concurrentConsumers"),
                            "active", consumer.getActiveConsumerCount(),
                            "prefetch", new org.springframework.beans.DirectFieldAccessor(consumer).getPropertyValue("prefetchCount"))),
                    Map.entry("capacity", environment.getRequiredProperty("ticketing.purchase.capacity")),
                    Map.entry("metrics", metrics), Map.entry("completions", completions.snapshot()),
                    Map.entry("redisCommands", redisCommands.snapshot()));
        }
    }
}
