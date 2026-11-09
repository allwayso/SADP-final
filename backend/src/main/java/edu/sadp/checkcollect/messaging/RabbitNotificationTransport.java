package edu.sadp.checkcollect.messaging;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 通过 RabbitMQ 投递通知标识。消费端再按“通知 + 收件人”幂等确认收件记录，
 * 因此重复投递不会产生重复收件人。
 */
@Component
@ConditionalOnProperty(name = "app.messaging.transport", havingValue = "rabbit", matchIfMissing = true)
public class RabbitNotificationTransport implements NotificationTransport {
    private static final Logger log = LoggerFactory.getLogger(RabbitNotificationTransport.class);

    private final RabbitTemplate rabbitTemplate;
    private final MessagingProperties properties;

    public RabbitNotificationTransport(RabbitTemplate rabbitTemplate, MessagingProperties properties) {
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
    }

    @Override
    public void send(UUID notificationId) {
        rabbitTemplate.convertAndSend(properties.exchange(), properties.routingKey(), notificationId.toString());
        log.debug("通知 {} 已发布到 RabbitMQ（{} / {}）", notificationId, properties.exchange(), properties.routingKey());
    }
}
