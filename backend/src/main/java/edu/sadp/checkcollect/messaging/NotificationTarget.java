package edu.sadp.checkcollect.messaging;

import edu.sadp.checkcollect.identity.Role;
import java.util.UUID;

/**
 * 通知接收目标：单个用户，或创建通知时该角色下全部启用的用户。
 * 两种目标互斥，对应数据库 notifications 表的 target_kind 约束。
 */
public record NotificationTarget(UUID userId, Role role) {
    public NotificationTarget {
        if ((userId == null) == (role == null)) {
            throw new IllegalArgumentException("通知目标必须是单个用户或一个角色");
        }
    }

    public static NotificationTarget user(UUID userId) {
        return new NotificationTarget(userId, null);
    }

    public static NotificationTarget role(Role role) {
        return new NotificationTarget(null, role);
    }

    public boolean toUser() {
        return userId != null;
    }
}
