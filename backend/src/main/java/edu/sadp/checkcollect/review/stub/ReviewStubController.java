package edu.sadp.checkcollect.review.stub;

import edu.sadp.checkcollect.identity.IdentityService;
import edu.sadp.checkcollect.identity.Role;
import edu.sadp.checkcollect.identity.UserAccount;
import edu.sadp.checkcollect.review.port.ReviewSummary;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 联调用的桩检查接口：M2、M4 尚未接入时，用来核对 M3 写入地图的核验摘要和发出的通知。
 *
 * <p>只在对应桩生效时返回数据；真实适配器接入后返回 {@code 404}，因此不会成为生产接口。
 * 通知只返回当前登录用户有权看到的条目，与 M4 的收件人规则一致。
 */
@RestController
@RequestMapping("/api/review-stub")
public class ReviewStubController {

    private final ObjectProvider<InMemoryPoiRevisionStub> pois;
    private final ObjectProvider<RecordingNotificationStub> notifications;
    private final IdentityService identity;

    public ReviewStubController(ObjectProvider<InMemoryPoiRevisionStub> pois,
                                ObjectProvider<RecordingNotificationStub> notifications,
                                IdentityService identity) {
        this.pois = pois;
        this.notifications = notifications;
        this.identity = identity;
    }

    /** 桩中每个 POI 的最新版本与已同步的核验摘要，用于确认地图进展与核验状态一致。 */
    @GetMapping("/pois")
    public List<StubPoiView> pois() {
        UserAccount user = identity.currentUser();
        if (!user.roles().contains(Role.ADMIN) && !user.roles().contains(Role.REVIEWER)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅管理员和核验者可以查看桩的 POI 摘要");
        }
        InMemoryPoiRevisionStub stub = require(pois);
        return stub.latestRevisions().stream().map(revision -> {
            ReviewSummary summary = stub.summary(revision.poiId())
                    .map(InMemoryPoiRevisionStub.StoredSummary::summary).orElse(null);
            return new StubPoiView(revision.poiId(), revision.id(), revision.name(), revision.category(),
                    stub.history(revision.poiId()).size(), summary == null ? null : summary.progress().name(),
                    summary == null ? null : summary.note());
        }).toList();
    }

    /** 当前用户可见的通知桩记录，用于证明 M3 在每一步发出了正确类型、目标和关联的通知。 */
    @GetMapping("/notifications")
    public List<StubNotificationView> notifications() {
        UserAccount user = identity.currentUser();
        RecordingNotificationStub stub = require(notifications);
        return stub.visibleTo(user.id(), user.roles()).stream()
                .map(sent -> new StubNotificationView(sent.id(), sent.type(), target(sent),
                        sent.reference().type(), sent.reference().id(), sent.text(), sent.createdAt()))
                .toList();
    }

    /** 通知目标的可读形式：{@code USER:<id>} 或 {@code ROLE:<role>}，便于对照收件人规则。 */
    private static String target(RecordingNotificationStub.SentNotification sent) {
        return sent.target().userId() != null ? "USER:" + sent.target().userId() : "ROLE:" + sent.target().role();
    }

    private static <T> T require(ObjectProvider<T> provider) {
        T bean = provider.getIfAvailable();
        if (bean == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "对应桩未启用，模块已接入真实适配器");
        return bean;
    }

    public record StubPoiView(String poiId, String revisionId, String name, String category, int revisionCount,
                              String progress, String note) {
    }

    public record StubNotificationView(String id, String type, String target, String referenceType,
                                       String referenceId, String text, Instant createdAt) {
    }
}
