package edu.sadp.checkcollect.messaging;

import edu.sadp.checkcollect.identity.Role;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class NotificationRepository {
    private static final String VIEW_COLUMNS = """
            SELECT n.id, n.kind, n.title, n.body, n.reference_kind, n.reference_id, n.created_at,
                   r.read_at, r.delivered_at, u.display_name AS sender_name
            FROM notification_recipients r
            JOIN notifications n ON n.id = r.notification_id
            LEFT JOIN app_users u ON u.id = n.sender_id
            """;

    private final JdbcTemplate jdbc;

    public NotificationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 写入通知并按创建时的接收快照建立收件记录。收件记录先不标记已投递，
     * 由消费端幂等确认，因此中间件短暂不可用时用户仍能看到通知并稍后完成投递。
     */
    @Transactional
    public void create(NotificationRecord notification, List<UUID> recipients) {
        NotificationTarget target = notification.target();
        jdbc.update("""
                INSERT INTO notifications (id, kind, target_kind, target_user_id, target_role,
                                           reference_kind, reference_id, title, body, sender_id,
                                           status, attempts, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, ?)
                """,
                notification.id(), notification.kind().name(),
                target.toUser() ? "USER" : "ROLE", target.userId(),
                target.role() == null ? null : target.role().name(),
                notification.reference().kind(), notification.reference().id(),
                notification.title(), notification.body(), notification.senderId(),
                Timestamp.from(notification.createdAt()));
        for (UUID recipient : recipients) {
            jdbc.update("INSERT INTO notification_recipients (notification_id, user_id) VALUES (?, ?)",
                    notification.id(), recipient);
        }
    }

    public Optional<NotificationRecord> find(UUID id) {
        return jdbc.query("SELECT * FROM notifications WHERE id = ?", this::mapNotification, id).stream().findFirst();
    }

    public List<UUID> findRetryable(int maxAttempts, Instant attemptsBefore, int limit) {
        return jdbc.query("""
                        SELECT id FROM notifications
                        WHERE status <> 'DELIVERED' AND attempts < ?
                          AND (last_attempt_at IS NULL OR last_attempt_at < ?)
                        ORDER BY created_at
                        LIMIT ?
                        """,
                (rs, row) -> rs.getObject("id", UUID.class),
                maxAttempts, Timestamp.from(attemptsBefore), limit);
    }

    public void recordAttempt(UUID id) {
        jdbc.update("UPDATE notifications SET attempts = attempts + 1, last_attempt_at = NOW() WHERE id = ?", id);
    }

    public void markFailed(UUID id, String error) {
        jdbc.update("UPDATE notifications SET status = 'FAILED', last_error = ? WHERE id = ? AND status <> 'DELIVERED'",
                error, id);
    }

    /** 幂等：只有第一次从非 DELIVERED 变为 DELIVERED 时返回 1。 */
    public int markDelivered(UUID id) {
        return jdbc.update("""
                UPDATE notifications
                SET status = 'DELIVERED', delivered_at = COALESCE(delivered_at, NOW())
                WHERE id = ? AND status <> 'DELIVERED'
                """, id);
    }

    /** 幂等：重复消费不会重复“确认”同一收件人。 */
    public int markRecipientsDelivered(UUID id) {
        return jdbc.update("""
                UPDATE notification_recipients
                SET delivered_at = NOW()
                WHERE notification_id = ? AND delivered_at IS NULL
                """, id);
    }

    public int countRecipients(UUID id) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM notification_recipients WHERE notification_id = ?",
                Long.class, id);
        return count == null ? 0 : count.intValue();
    }

    public List<NotificationView> listForUser(UUID userId, int size, int offset) {
        return jdbc.query(VIEW_COLUMNS + """
                        WHERE r.user_id = ?
                        ORDER BY n.created_at DESC, n.id
                        LIMIT ? OFFSET ?
                        """,
                (rs, row) -> new NotificationView(
                        rs.getObject("id", UUID.class),
                        NotificationKind.valueOf(rs.getString("kind")),
                        rs.getString("title"),
                        rs.getString("body"),
                        rs.getString("reference_kind"),
                        rs.getString("reference_id"),
                        rs.getString("sender_name"),
                        instant(rs, "created_at"),
                        rs.getTimestamp("read_at") != null,
                        instant(rs, "read_at")),
                userId, size, offset);
    }

    public long countForUser(UUID userId) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM notification_recipients WHERE user_id = ?",
                Long.class, userId);
        return count == null ? 0 : count;
    }

    public long unreadCount(UUID userId) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM notification_recipients WHERE user_id = ? AND read_at IS NULL",
                Long.class, userId);
        return count == null ? 0 : count;
    }

    public int markRead(UUID userId, UUID notificationId) {
        return jdbc.update("""
                UPDATE notification_recipients
                SET read_at = COALESCE(read_at, NOW())
                WHERE user_id = ? AND notification_id = ?
                """, userId, notificationId);
    }

    public int markAllRead(UUID userId) {
        return jdbc.update("""
                UPDATE notification_recipients SET read_at = NOW()
                WHERE user_id = ? AND read_at IS NULL
                """, userId);
    }

    public boolean isRecipient(UUID notificationId, UUID userId) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM notification_recipients WHERE notification_id = ? AND user_id = ?)
                """, Boolean.class, notificationId, userId);
        return Boolean.TRUE.equals(exists);
    }

    public boolean isSender(UUID notificationId, UUID userId) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM notifications WHERE id = ? AND sender_id = ?)
                """, Boolean.class, notificationId, userId);
        return Boolean.TRUE.equals(exists);
    }

    public Map<String, Long> countByStatus() {
        Map<String, Long> counts = new java.util.LinkedHashMap<>();
        counts.put("PENDING", 0L);
        counts.put("DELIVERED", 0L);
        counts.put("FAILED", 0L);
        jdbc.query("SELECT status, count(*) AS total FROM notifications GROUP BY status",
                (rs, row) -> Map.entry(rs.getString("status"), rs.getLong("total")))
            .forEach(entry -> counts.put(entry.getKey(), entry.getValue()));
        return counts;
    }

    public List<NotificationDeliveryRow> recentForSender(UUID senderId, int limit) {
        return jdbc.query("""
                        SELECT n.id, n.kind, n.title, n.status, n.attempts, n.last_error, n.created_at,
                               n.target_kind, n.target_role, u.display_name AS target_name,
                               (SELECT count(*) FROM notification_recipients r WHERE r.notification_id = n.id) AS recipients,
                               (SELECT count(*) FROM notification_recipients r WHERE r.notification_id = n.id
                                  AND r.delivered_at IS NOT NULL) AS delivered
                        FROM notifications n
                        LEFT JOIN app_users u ON u.id = n.target_user_id
                        WHERE n.sender_id = ?
                        ORDER BY n.created_at DESC
                        LIMIT ?
                        """,
                (rs, row) -> new NotificationDeliveryRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("kind"),
                        rs.getString("title"),
                        rs.getString("status"),
                        rs.getInt("attempts"),
                        rs.getString("last_error"),
                        targetLabel(rs),
                        rs.getLong("recipients"),
                        rs.getLong("delivered"),
                        instant(rs, "created_at")),
                senderId, limit);
    }

    private static String targetLabel(ResultSet rs) throws SQLException {
        if ("ROLE".equals(rs.getString("target_kind"))) {
            return "角色 " + rs.getString("target_role");
        }
        String name = rs.getString("target_name");
        return name == null ? "指定用户" : "用户 " + name;
    }

    private NotificationRecord mapNotification(ResultSet rs, int row) throws SQLException {
        UUID targetUser = rs.getObject("target_user_id", UUID.class);
        String targetRole = rs.getString("target_role");
        NotificationTarget target = targetUser != null
                ? NotificationTarget.user(targetUser)
                : NotificationTarget.role(Role.valueOf(targetRole));
        String referenceKind = rs.getString("reference_kind");
        String referenceId = rs.getString("reference_id");
        return new NotificationRecord(
                rs.getObject("id", UUID.class),
                NotificationKind.valueOf(rs.getString("kind")),
                target,
                referenceKind == null ? NotificationReference.NONE : NotificationReference.of(referenceKind, referenceId),
                rs.getString("title"),
                rs.getString("body"),
                rs.getObject("sender_id", UUID.class),
                rs.getString("status"),
                rs.getInt("attempts"),
                rs.getString("last_error"),
                instant(rs, "created_at"),
                instant(rs, "last_attempt_at"),
                instant(rs, "delivered_at"));
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    /** 消息桩的投递诊断行，仅开发联调使用。 */
    public record NotificationDeliveryRow(UUID id, String kind, String title, String status, int attempts,
                                          String lastError, String target, long recipients, long delivered,
                                          Instant createdAt) {
    }
}
