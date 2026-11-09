package edu.sadp.checkcollect.review.port;

/**
 * M3 对 M4（消息与交流）的依赖接口，对应 work-allocation.md 约定的
 * {@code notify(type, target, reference, text)}。
 *
 * <p>M3 不直接发布 RabbitMQ 消息，也不依据消息结果决定业务状态。成员 D 提供真实实现后，
 * 将 {@code app.review.notification-stub} 设为 {@code false}。
 */
public interface NotificationPort {
    default String openConversation(String issueId, java.util.UUID collector, java.util.UUID reviewer) { return null; }
    default String conversationId(String issueId) { return null; }

    /**
     * 生成一条系统通知，由 M4 异步投递。通知失败不应撤销已提交的核验结果。
     *
     * @param type      通知类型，见 {@code ReviewNotifications}
     * @param target    点发用户或群发角色
     * @param reference 业务关联
     * @param text      通知正文
     * @return 通知标识
     */
    String notify(String type, NotificationTarget target, NotificationReference reference, String text);
}
