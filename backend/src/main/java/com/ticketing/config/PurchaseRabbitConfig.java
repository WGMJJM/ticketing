package com.ticketing.config;

import com.ticketing.messaging.PurchaseCommand;

import java.util.Map;
import org.springframework.amqp.core.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties(PurchaseConsumerProperties.class)
public class PurchaseRabbitConfig {
    @Bean
    SimpleRabbitListenerContainerFactory purchaseListenerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer, ConnectionFactory connection,
            PurchaseConsumerProperties properties) {
        var factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connection);
        factory.setConcurrentConsumers(properties.concurrency());
        factory.setMaxConcurrentConsumers(properties.concurrency());
        factory.setPrefetchCount(properties.prefetch());
        return factory;
    }

    @Bean
    Declarables purchaseTopology(@Value("${ticketing.purchase.exchange:ticketing.purchase}") String name,
            @Value("${ticketing.purchase.queue:ticketing.purchase.orders}") String queueName) {
        DirectExchange exchange=new DirectExchange(name,true,false);
        DirectExchange dead=new DirectExchange(name+".dead",true,false);
        Queue queue=new Queue(queueName,true,false,false,Map.of("x-dead-letter-exchange",dead.getName(),"x-dead-letter-routing-key","failed"));
        Queue deadQueue=new Queue(queueName+".dead",true);
        return new Declarables(exchange,dead,queue,deadQueue,
                BindingBuilder.bind(queue).to(exchange).with(PurchaseCommand.ROUTING_KEY),
                BindingBuilder.bind(deadQueue).to(dead).with("failed"));
    }
}
