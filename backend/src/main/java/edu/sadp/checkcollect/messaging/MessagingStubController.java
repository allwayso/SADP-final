package edu.sadp.checkcollect.messaging;

import edu.sadp.checkcollect.identity.IdentityService;
import edu.sadp.checkcollect.identity.Role;
import edu.sadp.checkcollect.identity.UserAccount;
import edu.sadp.checkcollect.identity.UserSummary;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 开发用消息桩：在 B/C 的真实采集、核验流程接入之前，以模拟数据触发 M4 的各类群发和点发，
 * 并暴露投递状态、故障注入、立即重试和重复投递，用于演示与联调。
 * 生产环境应设置 MESSAGING_STUB_ENABLED=false 关闭。
 */
@RestController
@RequestMapping("/api/messaging/stub")
@ConditionalOnProperty(name = "app.messaging.stub-enabled", havingValue = "true", matchIfMissing = false)
public class MessagingStubController {
    private final MessagingService messaging;
    private final IdentityService identity;
    private final NotificationRepository notifications;
    private final BrokerSimulation broker;
    private final MessagingProperties properties;

    public MessagingStubController(MessagingService messaging, IdentityService identity,
                                   NotificationRepository notifications, BrokerSimulation broker,
                                   MessagingProperties properties) {
        this.messaging = messaging;
        this.identity = identity;
        this.notifications = notifications;
        this.broker = broker;
        this.properties = properties;
    }

    public record ScenarioRequest(String scenario) {
    }

    public record ScenarioResult(String scenario, String summary, List<UUID> notificationIds, UUID conversationId) {
    }

    public record FaultRequest(boolean down) {
    }

    public record StubStatus(String transport, boolean brokerAvailable, long retryIntervalMs, int maxAttempts,
                             Map<String, Long> counts, List<NotificationRepository.NotificationDeliveryRow> recent) {
    }

    public record RetryResult(int retried, StubStatus status) {
    }

    @GetMapping("/status")
    public StubStatus status() {
        UUID userId = identity.requireRole(Role.ADMIN).id();
        Map<String, Long> counts = new LinkedHashMap<>(notifications.countByStatus());
        return new StubStatus(properties.transport(), broker.isAvailable(), properties.retryIntervalMs(),
                properties.maxAttempts(), counts, notifications.recentForSender(userId, 12));
    }

    @PostMapping("/scenario")
    public ScenarioResult scenario(@RequestBody(required = false) ScenarioRequest request) {
        identity.requireRole(Role.ADMIN);
        String scenario = request == null || request.scenario() == null
                ? "" : request.scenario().trim().toUpperCase(Locale.ROOT);
        return switch (scenario) {
            case "NEW_DATA" -> newData();
            case "ANNOUNCEMENT" -> announcement();
            case "ISSUE_RAISED" -> issueRaised();
            case "FIELD_TASK" -> fieldTask();
            case "FINAL_RESULT" -> finalResult();
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "未知演练场景，可选 NEW_DATA、ANNOUNCEMENT、ISSUE_RAISED、FIELD_TASK、FINAL_RESULT");
        };
    }

    @PostMapping("/fault")
    public StubStatus fault(@RequestBody FaultRequest request) {
        identity.requireRole(Role.ADMIN);
        broker.setAvailable(!request.down());
        return status();
    }

    @PostMapping("/retry")
    public RetryResult retry() {
        identity.requireRole(Role.ADMIN);
        int retried = messaging.retryPending(true);
        return new RetryResult(retried, status());
    }

    /** 模拟中间件重复投递同一条通知，用于验证“通知 + 收件人”去重。 */
    @PostMapping("/redeliver/{id}")
    public DeliveryResult redeliver(@PathVariable UUID id) {
        identity.requireRole(Role.ADMIN);
        return messaging.redeliver(id);
    }

    // ---------------------------------------------------------------- 演练场景

    private ScenarioResult newData() {
        String reference = "demo-" + shortId();
        UUID id = messaging.notifyRole(NotificationKind.NEW_DATA, Role.REVIEWER,
                NotificationReference.of("POI", reference),
                "有新的 POI 待核验",
                "采集者提交了点位 " + reference + "（模拟数据），请到核验页面查看照片与位置。");
        return new ScenarioResult("NEW_DATA", "已向全部核验者群发新数据通知（点位 " + reference + "）",
                List.of(id), null);
    }

    private ScenarioResult announcement() {
        UUID id = messaging.notifyRole(NotificationKind.ANNOUNCEMENT, Role.COLLECTOR,
                NotificationReference.NONE,
                "暂停采集通知",
                "因交通管制，今日 14:00 起暂停本区域采集工作，恢复时间另行通知。（模拟公告）");
        return new ScenarioResult("ANNOUNCEMENT", "已向全部采集者群发暂停工作公告", List.of(id), null);
    }

    private ScenarioResult issueRaised() {
        UserAccount me = identity.currentUser();
        UserSummary collector = pickUser(Role.COLLECTOR, me.id());
        if (collector == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "没有可用的采集者账号，请先用管理员创建或启用采集者");
        }
        String issueId = "issue-" + shortId();
        String poiReference = "demo-" + shortId();
        String body = "核验者认为点位 " + poiReference + " 的照片与描述不一致，请查看错误单并选择重新采集、更新或驳回。";
        UUID notification = messaging.notifyUser(NotificationKind.ISSUE_RAISED, collector.id(),
                NotificationReference.of("ISSUE", issueId), "你的采集数据被标记为错误", body);
        UUID conversation = messaging.openIssueConversation(issueId, "错误单 " + issueId + " 讨论",
                me.id(), collector.id());
        messaging.sendMessage(conversation, "这里的位置和现场不一致，请补充一张清晰的照片，谢谢。");
        return new ScenarioResult("ISSUE_RAISED",
                "已点发采集者 " + collector.displayName() + "，并建立错误单私聊（对方可在“交流”中回复）",
                List.of(notification), conversation);
    }

    private ScenarioResult fieldTask() {
        UserAccount me = identity.currentUser();
        UserSummary worker = pickUser(Role.FIELD_WORKER, me.id());
        if (worker == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "没有可用的第三方工作人员账号，请先用管理员创建或启用");
        }
        String taskId = "task-" + shortId();
        UUID id = messaging.notifyUser(NotificationKind.FIELD_TASK, worker.id(),
                NotificationReference.of("TASK", taskId),
                "你有一个现场复核任务",
                "请到点位 demo-" + shortId() + " 现场核对并提交现场采集版本与最终结论（模拟任务）。");
        return new ScenarioResult("FIELD_TASK", "已点发第三方工作人员 " + worker.displayName(), List.of(id), null);
    }

    private ScenarioResult finalResult() {
        UserAccount me = identity.currentUser();
        UserSummary collector = pickUser(Role.COLLECTOR, me.id());
        UserSummary reviewer = pickUser(Role.REVIEWER, me.id());
        if (collector == null || reviewer == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "需要至少一名采集者和一名核验者才能演示最终裁定通知");
        }
        String taskId = "task-" + shortId();
        NotificationReference reference = NotificationReference.of("TASK", taskId);
        List<UUID> ids = new ArrayList<>();
        ids.add(messaging.notifyUser(NotificationKind.FINAL_RESULT, collector.id(), reference,
                "异议已有最终结果",
                "第三方现场复核完成：以现场采集版本为准，原错误标识关闭。（模拟裁定）"));
        ids.add(messaging.notifyUser(NotificationKind.FINAL_RESULT, reviewer.id(), reference,
                "异议已有最终结果",
                "第三方现场复核完成：以现场采集版本为准，原错误标识关闭。（模拟裁定）"));
        return new ScenarioResult("FINAL_RESULT",
                "已分别点发当事双方：" + collector.displayName() + "、" + reviewer.displayName(),
                ids, null);
    }

    private UserSummary pickUser(Role role, UUID exclude) {
        List<UserSummary> users = identity.listUsers(role);
        return users.stream().filter(user -> !user.id().equals(exclude)).findFirst()
                .orElseGet(() -> users.isEmpty() ? null : users.get(0));
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
