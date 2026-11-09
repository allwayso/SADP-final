package edu.sadp.checkcollect.messaging;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * RabbitMQ 消费端：收到通知标识后执行幂等投递。重复消息只会确认一次收件记录。
 * 消费失败不重新入队（避免毒消息循环），由数据库中的待投递状态按周期重试。
 */
@Component
@ConditionalOnProperty(name = "app.messaging.transport", havingValue = "rabbit", matchIfMissing = true)
public class RabbitNotificationConsumer {
    private static final Logger log = LoggerFactory.getLogger(RabbitNotificationConsumer.class);

    private final NotificationDeliveryService delivery;

    public RabbitNotificationConsumer(NotificationDeliveryService delivery) {
        this.delivery = delivery;
    }

    @RabbitListener(queues = "${app.messaging.queue:checkcollect.notifications.delivery}")
    public void onNotification(String payload) {
        UUID notificationId;
        try {
            notificationId = UUID.fromString(payload);
        } catch (RuntimeException error) {
            log.warn("丢弃无法解析的通知消息：{}", payload);
            return;
        }
        try {
            DeliveryResult result = delivery.deliver(notificationId);
            log.info("消费通知 {}：状态 {}，收件人 {}，本次新增确认 {}",
                    notificationId, result.status(), result.recipients(), result.newlyDeliveredRecipients());
        } catch (RuntimeException error) {
            log.error("消费通知 {} 失败，等待按数据库状态重试：{}", notificationId, error.getMessage());
        }
    }
}
