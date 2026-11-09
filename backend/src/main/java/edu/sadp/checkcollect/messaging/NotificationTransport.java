package edu.sadp.checkcollect.messaging;

import java.util.UUID;

/** 把“某条通知需要投递”交给中间件。传输细节由实现类决定。 */
public interface NotificationTransport {
    void send(UUID notificationId);
}
