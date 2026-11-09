package edu.sadp.checkcollect.messaging;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 无 RabbitMQ 时的本地传输：直接调用同一套幂等投递逻辑，便于开发与联调。
 * 通过 MESSAGING_TRANSPORT=in-process 启用，不改变通知和收件记录的数据结构。
 */
@Component
@ConditionalOnProperty(name = "app.messaging.transport", havingValue = "in-process")
public class InProcessNotificationTransport implements NotificationTransport {
    private static final Logger log = LoggerFactory.getLogger(InProcessNotificationTransport.class);

    private final NotificationDeliveryService delivery;

    public InProcessNotificationTransport(NotificationDeliveryService delivery) {
        this.delivery = delivery;
    }

    @Override
    public void send(UUID notificationId) {
        DeliveryResult result = delivery.deliver(notificationId);
        log.debug("本地投递通知 {}：状态 {}，收件人 {}", notificationId, result.status(), result.recipients());
    }
}
