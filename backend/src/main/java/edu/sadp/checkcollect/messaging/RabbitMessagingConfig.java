package edu.sadp.checkcollect.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;

/** 一个应用实例消费一个通知队列，不为每个用户建立队列。 */
@Configuration
@EnableRabbit
@ConditionalOnProperty(name = "app.messaging.transport", havingValue = "rabbit", matchIfMissing = true)
public class RabbitMessagingConfig {

    @Bean
    TopicExchange notificationExchange(MessagingProperties properties) {
        return new TopicExchange(properties.exchange(), true, false);
    }

    @Bean
    Queue notificationQueue(MessagingProperties properties) {
        return QueueBuilder.durable(properties.queue()).build();
    }

    @Bean
    Binding notificationBinding(Queue notificationQueue, TopicExchange notificationExchange,
                                MessagingProperties properties) {
        return BindingBuilder.bind(notificationQueue).to(notificationExchange).with(properties.routingKey());
    }
}
