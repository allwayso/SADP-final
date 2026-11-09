package edu.sadp.checkcollect.messaging;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 浏览器使用的通知与聊天接口。请求身份一律取自登录态。 */
@RestController
@RequestMapping("/api")
public class MessagingController {
    private final MessagingService messaging;

    public MessagingController(MessagingService messaging) {
        this.messaging = messaging;
    }

    public record AnnouncementRequest(String title, String body) {}

    @PostMapping("/announcements")
    public Map<String, UUID> announce(@RequestBody AnnouncementRequest request) {
        return Map.of("id",messaging.announce(request.title(),request.body()));
    }

    public record MessageRequest(String body) {
    }

    @GetMapping("/notifications")
    public NotificationPage notifications(@RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        return messaging.notifications(page, size);
    }

    @GetMapping("/notifications/unread-count")
    public Map<String, Long> unreadCount() {
        return Map.of("unread", messaging.unreadCount());
    }

    @PostMapping("/notifications/{id}/read")
    public Map<String, Boolean> markRead(@PathVariable UUID id) {
        messaging.markRead(id);
        return Map.of("read", true);
    }

    @PostMapping("/notifications/read-all")
    public Map<String, Integer> markAllRead() {
        return Map.of("updated", messaging.markAllRead());
    }

    @GetMapping("/conversations")
    public List<Conversation> conversations() {
        return messaging.conversations();
    }

    @GetMapping("/conversations/{id}/members")
    public List<ConversationMember> members(@PathVariable UUID id) {
        return messaging.members(id);
    }

    @GetMapping("/conversations/{id}/messages")
    public List<ChatMessageView> messages(@PathVariable UUID id,
                                          @RequestParam(required = false) String after) {
        return messaging.messages(id, parseInstant(after));
    }

    @PostMapping("/conversations/{id}/messages")
    public ChatMessageView send(@PathVariable UUID id, @RequestBody(required = false) MessageRequest request) {
        return messaging.sendMessage(id, request == null ? null : request.body());
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "时间参数格式无效");
        }
    }
}
