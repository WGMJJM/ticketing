package com.ticketing;

import com.ticketing.checkin.CheckinProperties;
import com.ticketing.messaging.OutboxProperties;
import com.ticketing.trade.OrderProperties;
import com.ticketing.trade.AftercareProperties;
import com.ticketing.waitlist.WaitlistProperties;
import com.ticketing.admission.PurchaseProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.TestConfiguration;
import static org.assertj.core.api.Assertions.assertThat;

class ConfigurationBindingTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Properties.class);

    @Test
    void legacyKeysAndDefaultsRemainCompatible() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(OrderProperties.class).maxPerUserSession()).isEqualTo(4);
            assertThat(context.getBean(AftercareProperties.class).batchSize()).isEqualTo(100);
            assertThat(context.getBean(OutboxProperties.class).batchSize()).isEqualTo(100);
            assertThat(context.getBean(CheckinProperties.class).opensBeforeMinutes()).isEqualTo(60);
            assertThat(context.getBean(WaitlistProperties.class).dispatchMaxSteps()).isEqualTo(50);
            assertThat(context.getBean(PurchaseProperties.class).capacity()).isEqualTo(200);
        });
        runner.withPropertyValues("ticketing.order.payment-window-minutes=3", "ticketing.waitlist.outbox.batch-size=20")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(OrderProperties.class).paymentWindowMinutes()).isEqualTo(3);
                    assertThat(context.getBean(OutboxProperties.class).batchSize()).isEqualTo(20);
                });
    }

    @Test
    void invalidRelatedConfigurationFailsStartup() {
        for (String property : new String[] {"ticketing.order.max-per-user-session=0",
                "ticketing.order.expiry.batch-size=501", "ticketing.waitlist.outbox.batch-size=101",
                "ticketing.waitlist.outbox.retry-max-seconds=1", "ticketing.checkin.opens-before-minutes=1441",
                "ticketing.waitlist.compensation-batch-size=0", "ticketing.order.aftercare.batch-size=501",
                "ticketing.order.aftercare.max-attempts=1", "ticketing.order.aftercare.retry-delay-seconds=0",
                "ticketing.purchase.capacity=0", "ticketing.purchase.queue-seconds=4", "ticketing.purchase.lease-seconds=0"}) {
            runner.withPropertyValues(property).run(context -> assertThat(context).hasFailed());
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties({OrderProperties.class, AftercareProperties.class, OutboxProperties.class, CheckinProperties.class, WaitlistProperties.class, PurchaseProperties.class})
    static class Properties { }
}
