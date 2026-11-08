package edu.sadp.checkcollect.review.stub;

import edu.sadp.checkcollect.identity.Role;
import edu.sadp.checkcollect.review.port.NotificationPort;
import edu.sadp.checkcollect.review.port.NotificationReference;
import edu.sadp.checkcollect.review.port.NotificationTarget;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * M4 尚未接入时的通知桩：只记录调用，不实际投递。用于证明 M3 在每个流转节点发出了
 * 正确类型、目标和关联的通知。数据保存在内存，应用重启后清空。
 */
public class RecordingNotificationStub implements NotificationPort {

    public record SentNotification(String id, String type, NotificationTarget target,
                                   NotificationReference reference, String text, Instant createdAt) {
        public boolean visibleTo(UUID userId, Set<Role> roles) {
            return userId.equals(target.userId()) || (target.role() != null && roles.contains(target.role()));
        }
    }

    private final List<SentNotification> sent = new ArrayList<>();

    @Override
    public synchronized String notify(String type, NotificationTarget target, NotificationReference reference, String text) {
        SentNotification notification = new SentNotification("ntf-" + UUID.randomUUID(), type, target, reference, text, Instant.now());
        sent.add(notification);
        return notification.id();
    }

    public synchronized List<SentNotification> sent() {
        return List.copyOf(sent);
    }

    public synchronized List<SentNotification> visibleTo(UUID userId, Set<Role> roles) {
        List<SentNotification> visible = new ArrayList<>(sent.stream().filter(item -> item.visibleTo(userId, roles)).toList());
        java.util.Collections.reverse(visible);
        return visible;
    }

    public synchronized void clear() {
        sent.clear();
    }
}
