package com.ticketing.waitlist;

import com.ticketing.messaging.InventoryEvent;

import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "ticketing.waitlist.messaging.enabled", matchIfMissing = true)
public class WaitlistRabbitConfig {
    @Bean
    Declarables waitlistTopology(
            @Value("${ticketing.waitlist.exchange:ticketing.waitlist}") String exchangeName,
            @Value("${ticketing.waitlist.queue:ticketing.waitlist.dispatch}") String queueName) {
        DirectExchange exchange = new DirectExchange(exchangeName, true, false);
        DirectExchange dead = new DirectExchange(exchangeName + ".dead", true, false);
        Queue queue =
                new Queue(
                        queueName,
                        true,
                        false,
                        false,
                        Map.of(
                                "x-dead-letter-exchange",
                                dead.getName(),
                                "x-dead-letter-routing-key",
                                "failed"));
        Queue deadQueue = new Queue(queueName + ".dead", true);
        return new Declarables(
                exchange,
                dead,
                queue,
                deadQueue,
                BindingBuilder.bind(queue).to(exchange).with(InventoryEvent.ROUTING_KEY),
                BindingBuilder.bind(deadQueue).to(dead).with("failed"));
    }
}
