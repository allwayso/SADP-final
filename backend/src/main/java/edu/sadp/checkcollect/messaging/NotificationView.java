package edu.sadp.checkcollect.messaging;

import java.time.Instant;
import java.util.UUID;

/** 收件人看到的通知。已读状态按“通知 + 收件人”记录，不随投递状态丢失。 */
public record NotificationView(UUID id, NotificationKind kind, String title, String body,
                               String referenceKind, String referenceId, String senderName,
                               Instant createdAt, boolean read, Instant readAt) {
}
