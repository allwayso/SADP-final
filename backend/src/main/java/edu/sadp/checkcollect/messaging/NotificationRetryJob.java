package edu.sadp.checkcollect.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 周期补投数据库中未完成的通知，避免中间件短暂不可用造成通知丢失。 */
@Component
public class NotificationRetryJob {
    private static final Logger log = LoggerFactory.getLogger(NotificationRetryJob.class);

    private final NotificationPublisher publisher;

    public NotificationRetryJob(NotificationPublisher publisher) {
        this.publisher = publisher;
    }

    @Scheduled(initialDelayString = "${app.messaging.retry-interval-ms:15000}",
               fixedDelayString = "${app.messaging.retry-interval-ms:15000}")
    public void retryPending() {
        try {
            publisher.retryPending(false);
        } catch (RuntimeException error) {
            log.error("通知重试任务失败：{}", error.getMessage());
        }
    }
}
