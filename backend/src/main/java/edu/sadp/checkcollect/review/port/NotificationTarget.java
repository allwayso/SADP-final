package edu.sadp.checkcollect.review.port;

import edu.sadp.checkcollect.identity.Role;
import java.util.Objects;
import java.util.UUID;

/** 通知目标：点发给一个用户，或群发给一个角色，二者取其一。 */
public record NotificationTarget(UUID userId, Role role) {
    public NotificationTarget {
        if ((userId == null) == (role == null)) {
            throw new IllegalArgumentException("通知目标须且只能是用户或角色之一");
        }
    }

    public static NotificationTarget user(UUID userId) {
        return new NotificationTarget(Objects.requireNonNull(userId), null);
    }

    public static NotificationTarget role(Role role) {
        return new NotificationTarget(null, Objects.requireNonNull(role));
    }
}
