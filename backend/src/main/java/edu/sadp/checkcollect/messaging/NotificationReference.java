package edu.sadp.checkcollect.messaging;

/**
 * 通知指向的业务对象。M4 不解释业务含义，只保存并在页面上提供跳转线索。
 * 例如 POI / ISSUE / TASK。
 */
public record NotificationReference(String kind, String id) {
    public static final NotificationReference NONE = new NotificationReference(null, null);

    public NotificationReference {
        if (kind == null ^ id == null) {
            throw new IllegalArgumentException("通知关联必须同时提供类型和标识");
        }
        if (kind != null && (kind.length() > 20 || id.length() > 64)) {
            throw new IllegalArgumentException("通知关联标识过长");
        }
    }

    public static NotificationReference of(String kind, String id) {
        return new NotificationReference(kind, id);
    }

    public boolean isEmpty() {
        return kind == null;
    }
}
