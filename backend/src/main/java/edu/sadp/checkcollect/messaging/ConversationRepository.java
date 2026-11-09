package edu.sadp.checkcollect.messaging;

import edu.sadp.checkcollect.identity.Role;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ConversationRepository {
    private static final String SELECT_CONVERSATION = """
            SELECT c.id, c.kind, c.title, c.role, c.issue_id, c.created_at,
                   (SELECT max(m.created_at) FROM chat_messages m WHERE m.conversation_id = c.id) AS last_message_at,
                   (SELECT SUBSTRING(m.body, 1, 120) FROM chat_messages m WHERE m.conversation_id = c.id
                      ORDER BY m.created_at DESC, m.id DESC LIMIT 1) AS last_message_preview,
                   (SELECT u.display_name FROM chat_messages m JOIN app_users u ON u.id = m.sender_id
                      WHERE m.conversation_id = c.id ORDER BY m.created_at DESC, m.id DESC LIMIT 1) AS last_sender_name
            FROM conversations c
            """;

    private final JdbcTemplate jdbc;

    public ConversationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 预建角色群聊。role 上的唯一约束保证并发或重复启动时只有一个群。
     * 群成员不落 conversation_members，由角色动态确定，角色调整后自动生效。
     */
    public void ensureRoleGroup(Role role, String title) {
        Long existing = jdbc.queryForObject(
                "SELECT count(*) FROM conversations WHERE kind = 'ROLE_GROUP' AND role = ?", Long.class, role.name());
        if (existing != null && existing > 0) {
            return;
        }
        try {
            jdbc.update("INSERT INTO conversations (id, kind, title, role) VALUES (?, 'ROLE_GROUP', ?, ?)",
                    UUID.randomUUID(), title, role.name());
        } catch (DuplicateKeyException ignored) {
            // 并发启动时另一个实例已经创建。
        }
    }

    public Optional<Conversation> find(UUID id) {
        return jdbc.query(SELECT_CONVERSATION + " WHERE c.id = ?", this::mapConversation, id).stream().findFirst();
    }

    /**
     * 当前用户可见的会话：与其角色匹配的群聊，以及其作为当事一方参与的错误单私聊。
     */
    public List<Conversation> listForUser(UUID userId, Set<Role> roles) {
        String rolePlaceholders = roles.isEmpty()
                ? "'__none__'"
                : roles.stream().map(role -> "?").collect(Collectors.joining(", "));
        String sql = SELECT_CONVERSATION + """
                WHERE (c.kind = 'ROLE_GROUP' AND c.role IN (%s))
                   OR (c.kind = 'ISSUE' AND EXISTS (
                        SELECT 1 FROM conversation_members cm
                        WHERE cm.conversation_id = c.id AND cm.user_id = ?))
                ORDER BY COALESCE((SELECT max(m.created_at) FROM chat_messages m WHERE m.conversation_id = c.id),
                                  c.created_at) DESC, c.title
                """.formatted(rolePlaceholders);
        Object[] args = new Object[roles.size() + 1];
        int index = 0;
        for (Role role : roles) {
            args[index++] = role.name();
        }
        args[index] = userId;
        return jdbc.query(sql, this::mapConversation, args);
    }

    public Optional<Conversation> findIssueConversation(String issueId) {
        return jdbc.query(SELECT_CONVERSATION + " WHERE c.kind = 'ISSUE' AND c.issue_id = ?",
                this::mapConversation, issueId).stream().findFirst();
    }

    @Transactional
    public UUID createIssueConversation(String issueId, String title, Collection<UUID> participants) {
        Optional<UUID> existing = findIssueConversation(issueId).map(Conversation::id);
        if (existing.isPresent()) {
            return existing.get();
        }
        UUID id = UUID.randomUUID();
        int created=jdbc.update("INSERT INTO conversations (id, kind, title, issue_id) VALUES (?, 'ISSUE', ?, ?) ON CONFLICT DO NOTHING", id,title,issueId);
        if (created==0) return findIssueConversation(issueId).map(Conversation::id).orElseThrow();
        for (UUID participant : participants) {
            jdbc.update("INSERT INTO conversation_members (conversation_id, user_id) VALUES (?, ?)", id, participant);
        }
        return id;
    }

    public boolean isMember(UUID conversationId, UUID userId) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM conversation_members WHERE conversation_id = ? AND user_id = ?)
                """, Boolean.class, conversationId, userId);
        return Boolean.TRUE.equals(exists);
    }

    public List<ConversationMember> members(UUID conversationId) {
        return jdbc.query("""
                        SELECT u.id, u.display_name FROM conversation_members cm
                        JOIN app_users u ON u.id = cm.user_id
                        WHERE cm.conversation_id = ?
                        ORDER BY u.display_name
                        """,
                (rs, row) -> new ConversationMember(rs.getObject("id", UUID.class), rs.getString("display_name")),
                conversationId);
    }

    /** 增量读取：after 为空时取最近 limit 条，否则取该时刻之后的消息，按时间升序。 */
    public List<ChatMessageView> messages(UUID conversationId, Instant after, int limit) {
        RowMapper<ChatMessageView> mapper = (rs, row) -> new ChatMessageView(
                rs.getObject("id", UUID.class),
                rs.getObject("conversation_id", UUID.class),
                rs.getObject("sender_id", UUID.class),
                rs.getString("sender_name"),
                rs.getString("body"),
                NotificationRepository.instant(rs, "created_at"));
        if (after == null) {
            return jdbc.query("""
                            SELECT * FROM (
                                SELECT m.id, m.conversation_id, m.sender_id, u.display_name AS sender_name,
                                       m.body, m.created_at
                                FROM chat_messages m JOIN app_users u ON u.id = m.sender_id
                                WHERE m.conversation_id = ?
                                ORDER BY m.created_at DESC, m.id DESC
                                LIMIT ?
                            ) recent
                            ORDER BY recent.created_at ASC, recent.id ASC
                            """,
                    mapper, conversationId, limit);
        }
        return jdbc.query("""
                        SELECT m.id, m.conversation_id, m.sender_id, u.display_name AS sender_name,
                               m.body, m.created_at
                        FROM chat_messages m JOIN app_users u ON u.id = m.sender_id
                        WHERE m.conversation_id = ? AND m.created_at > ?
                        ORDER BY m.created_at ASC, m.id ASC
                        LIMIT ?
                        """,
                mapper, conversationId, Timestamp.from(after), limit);
    }

    @Transactional
    public ChatMessageView addMessage(UUID conversationId, UUID senderId, String body) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO chat_messages (id, conversation_id, sender_id, body) VALUES (?, ?, ?, ?)",
                id, conversationId, senderId, body);
        return jdbc.query("""
                        SELECT m.id, m.conversation_id, m.sender_id, u.display_name AS sender_name,
                               m.body, m.created_at
                        FROM chat_messages m JOIN app_users u ON u.id = m.sender_id
                        WHERE m.id = ?
                        """,
                (rs, row) -> new ChatMessageView(
                        rs.getObject("id", UUID.class),
                        rs.getObject("conversation_id", UUID.class),
                        rs.getObject("sender_id", UUID.class),
                        rs.getString("sender_name"),
                        rs.getString("body"),
                        NotificationRepository.instant(rs, "created_at")),
                id).get(0);
    }

    private Conversation mapConversation(ResultSet rs, int row) throws SQLException {
        String role = rs.getString("role");
        return new Conversation(
                rs.getObject("id", UUID.class),
                rs.getString("kind"),
                rs.getString("title"),
                role == null ? null : Role.valueOf(role),
                rs.getString("issue_id"),
                NotificationRepository.instant(rs, "created_at"),
                NotificationRepository.instant(rs, "last_message_at"),
                rs.getString("last_message_preview"),
                rs.getString("last_sender_name"));
    }
}
