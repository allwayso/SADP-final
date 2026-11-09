package edu.sadp.checkcollect.messaging;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 通知发布与失败重试。先记录一次投递尝试，再交给传输层；失败只更新状态，
 * 不撤销已经提交的采集或核验结果，等待重试任务补投。
 */
@Service
public class NotificationPublisher {
    private static final Logger log = LoggerFactory.getLogger(NotificationPublisher.class);
    private static final int RETRY_BATCH = 50;

    private final NotificationRepository notifications;
    private final NotificationTransport transport;
    private final BrokerSimulation broker;
    private final MessagingProperties properties;

    public NotificationPublisher(NotificationRepository notifications, NotificationTransport transport,
                                 BrokerSimulation broker, MessagingProperties properties) {
        this.notifications = notifications;
        this.transport = transport;
        this.broker = broker;
        this.properties = properties;
    }

    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void publish(UUID notificationId) {
        notifications.recordAttempt(notificationId);
        try {
            broker.assertAvailable();
            transport.send(notificationId);
        } catch (RuntimeException error) {
            String message = shorten(error.getMessage());
            notifications.markFailed(notificationId, message);
            log.warn("通知 {} 发布失败，等待重试：{}", notificationId, message);
        }
    }

    /** 定时重试使用退避间隔；消息桩的“立即重试”忽略退避。 */
    public int retryPending(boolean immediate) {
        Instant cutoff = immediate
                ? Instant.now().plusSeconds(1)
                : Instant.now().minusMillis(properties.retryIntervalMs());
        List<UUID> pending = notifications.findRetryable(properties.maxAttempts(), cutoff, RETRY_BATCH);
        for (UUID notificationId : pending) {
            publish(notificationId);
        }
        if (!pending.isEmpty()) {
            log.info("本次尝试重试 {} 条待投递通知（最多 {} 次）", pending.size(), properties.maxAttempts());
        }
        return pending.size();
    }

    private static String shorten(String message) {
        if (message == null || message.isBlank()) {
            return "未知错误";
        }
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }
}
