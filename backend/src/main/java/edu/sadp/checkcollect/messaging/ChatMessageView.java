package edu.sadp.checkcollect.messaging;

import java.time.Instant;
import java.util.UUID;

public record ChatMessageView(UUID id, UUID conversationId, UUID senderId, String senderName,
                              String body, Instant createdAt) {
}
