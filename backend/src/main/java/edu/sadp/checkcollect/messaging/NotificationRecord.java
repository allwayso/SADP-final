package edu.sadp.checkcollect.messaging;

import java.time.Instant;
import java.util.UUID;

/** 通知的服务端记录，投递状态以数据库为准。 */
public record NotificationRecord(UUID id, NotificationKind kind, NotificationTarget target,
                                 NotificationReference reference, String title, String body, UUID senderId,
                                 String status, int attempts, String lastError,
                                 Instant createdAt, Instant lastAttemptAt, Instant deliveredAt) {

    public boolean delivered() {
        return "DELIVERED".equals(status);
    }
}
