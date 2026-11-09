package edu.sadp.checkcollect.review.port;

/**
 * 通知的业务关联，点击通知后进入对应页面。
 *
 * @param type 关联对象类型：{@code ISSUE}、{@code FIELD_TASK}、{@code REVISION}
 * @param id   关联对象标识
 */
public record NotificationReference(String type, String id) {
    public static NotificationReference issue(Object id) { return new NotificationReference("ISSUE", String.valueOf(id)); }
    public static NotificationReference fieldTask(Object id) { return new NotificationReference("FIELD_TASK", String.valueOf(id)); }
    public static NotificationReference revision(String id) { return new NotificationReference("REVISION", id); }
}
