package edu.sadp.checkcollect.messaging;

import edu.sadp.checkcollect.identity.IdentityService;
import edu.sadp.checkcollect.identity.Role;
import edu.sadp.checkcollect.identity.UserAccount;
import edu.sadp.checkcollect.identity.UserSummary;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * M4 对外的业务接口。B/C 在提交采集、核验、异议动作时调用 notify*，M4 不判断业务结果；
 * 聊天由本模块负责持久化与访问控制。
 */
@Service
public class MessagingService {
    private static final int MAX_TITLE = 200;
    private static final int MAX_BODY = 2000;
    private static final int MESSAGE_PAGE = 200;

    private final IdentityService identity;
    private final NotificationRepository notifications;
    private final ConversationRepository conversations;
    private final NotificationPublisher publisher;
    private final NotificationDeliveryService delivery;

    public MessagingService(IdentityService identity, NotificationRepository notifications,
                            ConversationRepository conversations, NotificationPublisher publisher,
                            NotificationDeliveryService delivery) {
        this.identity = identity;
        this.notifications = notifications;
        this.conversations = conversations;
        this.publisher = publisher;
        this.delivery = delivery;
    }

    // ---------------------------------------------------------------- 通知

    @org.springframework.transaction.annotation.Transactional
    public UUID notifyRole(NotificationKind kind, Role role, NotificationReference reference, String title, String body) {
        return notifyRoleFrom(currentUser().id(), kind, role, reference, title, body);
    }

    /** 供后台任务或系统事件使用；senderId 可以为空表示系统发出。 */
    @org.springframework.transaction.annotation.Transactional
    public UUID notifyRoleFrom(UUID senderId, NotificationKind kind, Role role, NotificationReference reference,
                               String title, String body) {
        if (role == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "通知角色不能为空");
        }
        List<UUID> recipients = identity.listUsers(role).stream().map(UserSummary::id).toList();
        return store(kind, NotificationTarget.role(role), reference, title, body, senderId, recipients);
    }

    @org.springframework.transaction.annotation.Transactional
    public UUID notifyUser(NotificationKind kind, UUID recipientId, NotificationReference reference,
                           String title, String body) {
        return notifyUserFrom(currentUser().id(), kind, recipientId, reference, title, body);
    }

    @org.springframework.transaction.annotation.Transactional
    public UUID notifyUserFrom(UUID senderId, NotificationKind kind, UUID recipientId, NotificationReference reference,
                               String title, String body) {
        if (recipientId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "收件人不能为空");
        }
        return store(kind, NotificationTarget.user(recipientId), reference, title, body, senderId,
                List.of(recipientId));
    }

    private UUID store(NotificationKind kind, NotificationTarget target, NotificationReference reference,
                       String title, String body, UUID senderId, List<UUID> recipients) {
        if (kind == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "通知类型不能为空");
        }
        String safeTitle = requireText(title, MAX_TITLE, "通知标题");
        String safeBody = requireText(body, MAX_BODY, "通知内容");
        NotificationReference safeReference = reference == null ? NotificationReference.NONE : reference;
        UUID id = UUID.randomUUID();
        NotificationRecord record = new NotificationRecord(id, kind, target, safeReference, safeTitle, safeBody,
                senderId, "PENDING", 0, null, Instant.now(), null, null);
        try {
            notifications.create(record, recipients);
        } catch (DataIntegrityViolationException error) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "通知收件人不存在", error);
        }
        // 创建事务已经提交，再交给中间件；失败不影响业务提交，由重试任务补投。
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void afterCommit() { publisher.publish(id); }
                });
        } else { publisher.publish(id); }
        return id;
    }

    public NotificationPage notifications(int page, int size) {
        UserAccount user = currentUser();
        int safePage = Math.max(0, page);
        int safeSize = Math.min(100, Math.max(1, size));
        List<NotificationView> items = notifications.listForUser(user.id(), safeSize, safePage * safeSize);
        long total = notifications.countForUser(user.id());
        long unread = notifications.unreadCount(user.id());
        return new NotificationPage(items, total, safePage, safeSize, unread);
    }

    public long unreadCount() {
        return notifications.unreadCount(currentUser().id());
    }

    public void markRead(UUID notificationId) {
        UserAccount user = currentUser();
        if (notifications.markRead(user.id(), notificationId) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "通知不存在或不属于当前用户");
        }
    }

    public int markAllRead() {
        return notifications.markAllRead(currentUser().id());
    }

    // ---------------------------------------------------------------- 会话与聊天

    /** 由 M3 在建立错误单时调用。同一错误单只创建一个私聊，参与人固定。 */
    public UUID openIssueConversation(String issueId, String title, UUID partyOne, UUID partyTwo) {
        String safeIssueId = requireText(issueId, 64, "错误单标识");
        String safeTitle = requireText(title, MAX_TITLE, "会话标题");
        if (partyOne == null || partyTwo == null || partyOne.equals(partyTwo)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "错误单私聊需要两名不同的当事用户");
        }
        try {
            return conversations.createIssueConversation(safeIssueId, safeTitle, List.of(partyOne, partyTwo));
        } catch (DataIntegrityViolationException error) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "错误单私聊的当事用户不存在", error);
        }
    }

    public String issueConversationId(String issueId) {
        UserAccount user=currentUser();
        return conversations.findIssueConversation(issueId).filter(c -> conversations.isMember(c.id(),user.id()))
            .map(c -> c.id().toString()).orElse(null);
    }

    public UUID announce(String title,String body) {
        identity.requireRole(Role.ADMIN);
        return notifyRole(NotificationKind.ANNOUNCEMENT,Role.COLLECTOR,NotificationReference.NONE,title,body);
    }

    public List<Conversation> conversations() {
        UserAccount user = currentUser();
        return conversations.listForUser(user.id(), user.roles());
    }

    public List<ChatMessageView> messages(UUID conversationId, Instant after) {
        UserAccount user = currentUser();
        requireAccess(conversationId, user);
        return conversations.messages(conversationId, after, MESSAGE_PAGE);
    }

    public ChatMessageView sendMessage(UUID conversationId, String body) {
        UserAccount user = currentUser();
        requireAccess(conversationId, user);
        String text = requireText(body, MAX_BODY, "消息内容");
        return conversations.addMessage(conversationId, user.id(), text);
    }

    public List<ConversationMember> members(UUID conversationId) {
        UserAccount user = currentUser();
        Conversation conversation = requireAccess(conversationId, user);
        if (conversation.role() != null) {
            return identity.listUsers(conversation.role()).stream()
                    .map(member -> new ConversationMember(member.id(), member.displayName()))
                    .toList();
        }
        return conversations.members(conversationId);
    }

    private Conversation requireAccess(UUID conversationId, UserAccount user) {
        Conversation conversation = conversations.find(conversationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在"));
        if (conversation.role() != null) {
            if (!user.roles().contains(conversation.role())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只有该角色的成员可以进入此群聊");
            }
        } else if (!conversations.isMember(conversationId, user.id())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只有当事双方可以进入该错误单私聊");
        }
        return conversation;
    }

    // ---------------------------------------------------------------- 投递诊断（消息桩使用）

    public int retryPending(boolean immediate) {
        currentUser();
        return publisher.retryPending(immediate);
    }

    public DeliveryResult redeliver(UUID notificationId) {
        UserAccount user = currentUser();
        if (!notifications.isRecipient(notificationId, user.id())
                && !notifications.isSender(notificationId, user.id())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只能重新投递自己发出或收到的通知");
        }
        return delivery.deliver(notificationId);
    }

    // ---------------------------------------------------------------- 辅助

    private UserAccount currentUser() {
        return identity.currentUser();
    }

    private static String requireText(String value, int maxLength, String field) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + "不能为空");
        }
        String text = value.trim();
        if (text.length() > maxLength) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + "不能超过" + maxLength + "个字符");
        }
        return text;
    }
}
