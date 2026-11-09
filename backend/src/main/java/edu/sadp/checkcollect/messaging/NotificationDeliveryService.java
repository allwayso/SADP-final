package edu.sadp.checkcollect.messaging;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 幂等投递：按“通知标识 + 收件人”确认收件记录并更新通知状态。
 * 重复消费时第二次不会再产生任何变更，因此不需要额外的去重表。
 */
@Service
public class NotificationDeliveryService {
    private static final Logger log = LoggerFactory.getLogger(NotificationDeliveryService.class);

    private final NotificationRepository notifications;

    public NotificationDeliveryService(NotificationRepository notifications) {
        this.notifications = notifications;
    }

    @Transactional
    public DeliveryResult deliver(UUID notificationId) {
        NotificationRecord notification = notifications.find(notificationId).orElse(null);
        if (notification == null) {
            log.warn("通知 {} 不存在，忽略投递", notificationId);
            return new DeliveryResult(notificationId, false, 0, 0, "MISSING");
        }
        int newlyDelivered = notifications.markRecipientsDelivered(notificationId);
        int recipients = notifications.countRecipients(notificationId);
        int changed = notifications.markDelivered(notificationId);
        String status = changed > 0 ? "DELIVERED" : notification.status();
        if (changed > 0) {
            log.info("通知 {} 投递完成：收件人 {}，本次确认 {}，类型 {}",
                    notificationId, recipients, newlyDelivered, notification.kind());
        } else {
            log.info("通知 {} 已投递过，忽略重复消费（收件人 {}）", notificationId, recipients);
        }
        return new DeliveryResult(notificationId, changed > 0, newlyDelivered, recipients, status);
    }
}
