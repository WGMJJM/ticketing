package com.ticketing.messaging;

import java.util.Map;
import org.springframework.amqp.core.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="ticketing.purchase.messaging-enabled",matchIfMissing=true)
public class PurchaseRabbitConfig {
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
