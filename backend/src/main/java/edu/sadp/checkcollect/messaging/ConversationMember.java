package edu.sadp.checkcollect.messaging;

import java.util.UUID;

public record ConversationMember(UUID id, String displayName) {
}
