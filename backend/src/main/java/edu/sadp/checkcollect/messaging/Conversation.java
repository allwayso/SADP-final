package edu.sadp.checkcollect.messaging;

import edu.sadp.checkcollect.identity.Role;
import java.time.Instant;
import java.util.UUID;

/**
 * 会话：错误单私聊（ISSUE）或按角色自动加入的群聊（ROLE_GROUP）。
 * 角色群聊的 role 非空，成员按当前角色动态确定；错误单私聊的成员在创建时固定。
 */
public record Conversation(UUID id, String kind, String title, Role role, String issueId,
                           Instant createdAt, Instant lastMessageAt, String lastMessagePreview,
                           String lastSenderName) {

    public boolean roleGroup() {
        return role() != null;
    }
}
