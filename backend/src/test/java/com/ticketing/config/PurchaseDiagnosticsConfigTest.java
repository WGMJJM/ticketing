package com.ticketing.config;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.apache.ibatis.builder.StaticSqlSource;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

class PurchaseDiagnosticsConfigTest {
    @Test
    void shutdownRequiresIndependentTokenAndExactLocalManagementPost() {
        String token = "a".repeat(64);
        var environment = new MockEnvironment().withProperty("ticketing.diagnostics.shutdown-token", token);
        var request = new org.springframework.mock.web.MockHttpServletRequest("POST", "/actuator/shutdown");
        request.setLocalPort(18081);
        request.setRemoteAddr("127.0.0.1");
        assertThat(PurchaseDiagnosticsConfig.authorizedShutdown(request, environment)).isFalse();
        request.addHeader("X-Ticketing-Diagnostics-Stop", token);
        assertThat(PurchaseDiagnosticsConfig.authorizedShutdown(request, environment)).isTrue();
        request.setLocalPort(18080);
        assertThat(PurchaseDiagnosticsConfig.authorizedShutdown(request, environment)).isFalse();
        request.setLocalPort(18081);
        request.setRemoteAddr("192.0.2.1");
        assertThat(PurchaseDiagnosticsConfig.authorizedShutdown(request, environment)).isFalse();
        request.setRemoteAddr("127.0.0.1");
        request.setMethod("GET");
        assertThat(PurchaseDiagnosticsConfig.authorizedShutdown(request, environment)).isFalse();
        request.setMethod("POST");
        request.setRequestURI("/api/v1/orders");
        assertThat(PurchaseDiagnosticsConfig.authorizedShutdown(request, environment)).isFalse();
        request.setRequestURI("/actuator/shutdown");
        environment.setProperty("ticketing.diagnostics.shutdown-token", "b".repeat(64));
        assertThat(PurchaseDiagnosticsConfig.authorizedShutdown(request, environment)).isFalse();
        environment.setProperty("ticketing.diagnostics.shutdown-token", "");
        assertThat(PurchaseDiagnosticsConfig.authorizedShutdown(request, environment)).isFalse();
    }

    @Test
    void frameworkCursorQueryRegistersCompletionWithoutConsumingOrClosingCursor() throws Exception {
        var registry = new SimpleMeterRegistry();
        var configuration = new Configuration();
        var statement = new MappedStatement.Builder(configuration, "com.ticketing.mapper.TicketSkuMapper.selectWithCursor",
                new StaticSqlSource(configuration, "SELECT * FROM ticket_sku WHERE id=1 FOR UPDATE"), SqlCommandType.SELECT).build();
        var executor = mock(Executor.class);
        var cursor = mock(org.apache.ibatis.cursor.Cursor.class);
        when(executor.queryCursor(statement, null, RowBounds.DEFAULT)).thenReturn(cursor);
        Executor observed = (Executor) new PurchaseDiagnosticsConfig.SqlDiagnostics(registry).plugin(executor);
        TransactionSynchronizationManager.initSynchronization();
        try {
            assertThat(observed.queryCursor(statement, null, RowBounds.DEFAULT)).isSameAs(cursor);
            var synchronizations = TransactionSynchronizationManager.getSynchronizations();
            assertThat(synchronizations).hasSize(1);
            synchronizations.getFirst().afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
            assertThat(registry.find("ticketing.diagnostics.duration").tag("stage", "stock.held.client").timer().count()).isEqualTo(1);
            org.mockito.Mockito.verifyNoInteractions(cursor);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            registry.close();
        }
    }

    @Test
    void frameworkSixArgumentQueryIsObservedThroughMybatisPlugin() throws Exception {
        var registry = new SimpleMeterRegistry();
        var configuration = new Configuration();
        var statement = new MappedStatement.Builder(configuration, "com.ticketing.mapper.TicketSkuMapper.selectList",
                new StaticSqlSource(configuration, "SELECT * FROM ticket_sku WHERE id=1 FOR UPDATE"), SqlCommandType.SELECT).build();
        var sql = statement.getBoundSql(null);
        var key = new org.apache.ibatis.cache.CacheKey();
        var executor = mock(Executor.class);
        List<Object> result = List.of(new Object());
        when(executor.query(statement, null, RowBounds.DEFAULT, null, key, sql)).thenReturn(result);
        Executor observed = (Executor) new PurchaseDiagnosticsConfig.SqlDiagnostics(registry).plugin(executor);
        TransactionSynchronizationManager.initSynchronization();
        try {
            assertThat(observed.query(statement, null, RowBounds.DEFAULT, null, key, sql)).isSameAs(result);
            assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
            assertThat(registry.find("ticketing.diagnostics.duration")
                    .tag("stage", "mapper.TicketSkuMapper.selectList").timer().count()).isEqualTo(1);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            registry.close();
        }
    }

    @Test
    void finalTransactionManagerMethodsAreDelegatedThroughInterfaceProxy() throws Exception {
        var registry = new SimpleMeterRegistry();
        var factory = new org.springframework.beans.factory.support.StaticListableBeanFactory();
        factory.addBean("meters", registry);
        var environment = new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:mysql://127.0.0.1:13307/ticketingjmeter?x=y")
                .withProperty("server.address", "127.0.0.1").withProperty("server.port", "18080")
                .withProperty("management.server.address", "127.0.0.1").withProperty("management.server.port", "18081");
        var source = mock(javax.sql.DataSource.class);
        var connection = mock(java.sql.Connection.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        Object proxy = PurchaseDiagnosticsConfig.purchaseStageDiagnostics(factory.getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class), environment,
                factory.getBeanProvider(PurchaseDiagnosticsConfig.CompletionLog.class))
                .postProcessAfterInitialization(new org.springframework.jdbc.datasource.DataSourceTransactionManager(source), "transactionManager");
        assertThat(org.springframework.aop.support.AopUtils.isJdkDynamicProxy(proxy)).isTrue();
        var template = new org.springframework.transaction.support.TransactionTemplate((org.springframework.transaction.PlatformTransactionManager) proxy);
        String result = template.execute(status -> "unchanged");
        assertThat(result).isEqualTo("unchanged");
        verify(connection).commit();
        assertThat(registry.find("ticketing.diagnostics.duration").tag("stage", "transaction.total").timer().count()).isEqualTo(1);
    }

    @Test
    void completionObservationIsBoundedDeduplicatedAndKeepsOnlyTerminalFacts() {
        var log = new PurchaseDiagnosticsConfig.CompletionLog();
        log.add(new com.ticketing.entity.PurchaseRequest(1, 1, 1, 1, "k", new byte[0], "QUEUED", null, null, null, null, null), 5);
        assertThat(log.snapshot().get("count")).isEqualTo(0L);
        for (long id=1; id<=513; id++) {
            var request = new com.ticketing.entity.PurchaseRequest(id, 1, 1, 1, "k", new byte[0], "SUCCEEDED", id, null, null, null, null);
            log.add(request, id+100);
            log.add(request, id+200);
        }
        assertThat(log.snapshot().get("count")).isEqualTo(513L);
        var recent = (java.util.Map<?,?>)log.snapshot().get("recent");
        assertThat(recent).hasSize(512);
        assertThat(recent.containsKey("1")).isFalse();
    }

    @Test
    void disabledByDefaultWithoutInfrastructureOrEndpoints() {
        new ApplicationContextRunner().withUserConfiguration(PurchaseDiagnosticsConfig.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean("purchaseSqlDiagnostics");
            assertThat(context).doesNotHaveBean("purchaseDiagnosticsEndpoint");
        });
    }

    @Test
    void rejectsBusinessDatabaseAndNonLoopbackManagement() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:mysql://127.0.0.1:13307/ticketingjmeter?connectionTimeZone=UTC")
                .withProperty("server.address", "127.0.0.1").withProperty("server.port", "18080")
                .withProperty("management.server.address", "127.0.0.1").withProperty("management.server.port", "18081");
        PurchaseDiagnosticsConfig.validateIsolation(environment);
        environment.setProperty("spring.datasource.url", "jdbc:mysql://127.0.0.1:3307/ticketing?connectionTimeZone=UTC");
        assertThatThrownBy(() -> PurchaseDiagnosticsConfig.validateIsolation(environment)).isInstanceOf(IllegalStateException.class);
        environment.setProperty("spring.datasource.url", "jdbc:mysql://127.0.0.1:13307/ticketingjmeter?connectionTimeZone=UTC");
        environment.setProperty("management.server.address", "0.0.0.0");
        assertThatThrownBy(() -> PurchaseDiagnosticsConfig.validateIsolation(environment)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void stockHoldEndsAtCompletionAndOriginalQueryResultIsPreserved() throws Throwable {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Configuration configuration = new Configuration();
        MappedStatement statement = new MappedStatement.Builder(configuration, "com.ticketing.mapper.TicketSkuMapper.selectList",
                new StaticSqlSource(configuration, "SELECT * FROM ticket_sku WHERE id=1 FOR UPDATE"), SqlCommandType.SELECT).build();
        Executor executor = mock(Executor.class);
        List<Object> result = List.of(new Object());
        when(executor.query(any(), any(), any(), any())).thenReturn(result);
        Invocation invocation = new Invocation(executor,
                Executor.class.getMethod("query", MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class),
                new Object[]{statement, null, RowBounds.DEFAULT, null});
        TransactionSynchronizationManager.initSynchronization();
        try {
            assertThat(new PurchaseDiagnosticsConfig.SqlDiagnostics(registry).intercept(invocation)).isSameAs(result);
            assertThat(registry.find("ticketing.diagnostics.duration").tag("stage", "stock.held.client").timer()).isNull();
            var synchronizations = TransactionSynchronizationManager.getSynchronizations();
            assertThat(synchronizations).hasSize(1);
            synchronizations.getFirst().afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
            assertThat(registry.find("ticketing.diagnostics.duration").tag("stage", "stock.held.client").timer().count()).isEqualTo(1);
            assertThat(registry.find("ticketing.diagnostics.stock.completions").tag("outcome", "committed").counter().count()).isEqualTo(1);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            registry.close();
        }
    }
}
