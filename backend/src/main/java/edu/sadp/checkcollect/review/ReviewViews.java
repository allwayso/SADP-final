package edu.sadp.checkcollect.review;

import edu.sadp.checkcollect.review.port.PoiRevision;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** M3 对外交换的请求与响应 DTO，不暴露数据库实体。 */
public final class ReviewViews {
    private ReviewViews() {}

    // ---- 请求 ----

    /** 核验指定版本：{@code PASSED} 通过，{@code ISSUE} 标识错误并须填写说明。 */
    public record ReviewRequest(String revisionId, ReviewModels.Outcome outcome, String description) {}

    /**
     * 错误单动作。{@code revisionId} 为用户操作时看到的当前版本，与服务端不一致时拒绝，
     * 防止对已更新的内容作出过期判断。
     */
    public record IssueActionRequest(String revisionId, String note) {}

    /** 采集内容，复用 M2 的采集表单字段。位置来自设备定位，不提供手工录入入口。 */
    public record CollectionInput(String category, String name, String description, Double latitude, Double longitude,
                                  Double accuracyMeters, String coordinateSystem, Instant locatedAt, String photoId) {}

    /** 采集者针对错误单的整改：重新采集或更新，形成新版本。 */
    public record CorrectionRequest(String revisionId, CollectionInput collection, String note) {}

    public record DisputeRequest(UUID issueId, String revisionId, String reason) {}

    /** 管理员调整现场任务的处理人；{@code assigneeId} 为空时按负载自动选择。 */
    public record AssignRequest(UUID assigneeId) {}

    /** 第三方现场结果。{@code REPLACED} 必须附带现场采集内容；{@code ORIGINAL_CORRECT} 可选附带作为依据。 */
    public record FieldResultRequest(Verdict verdict, String reason, CollectionInput collection) {}

    // ---- 响应 ----

    public record UserRef(UUID id, String displayName) {}

    public record ReviewRecordView(UUID id, String poiId, String revisionId, UserRef reviewer,
                                   ReviewModels.Outcome outcome, UUID issueId, Instant createdAt) {}

    /** 待检项：待核验的采集版本及其提交人，核验页面可直接展示，无需再查身份服务。 */
    public record PendingReview(PoiRevision revision, UserRef collector) {}

    public record IssueView(UUID id, String poiId, String flaggedRevisionId, String currentRevisionId,
                            String finalRevisionId, IssueStatus status, String description, UserRef collector,
                            UserRef reviewer, Instant createdAt, Instant updatedAt, Instant closedAt) {}

    public record EventView(IssueAction action, UserRef actor, IssueStatus fromStatus, IssueStatus toStatus,
                            String revisionId, String note, Instant createdAt) {}

    public record DisputeView(UUID id, UUID issueId, UserRef submittedBy, String reason, String status,
                              Instant createdAt, Instant decidedAt) {}

    public record TaskView(UUID id, UUID disputeId, UUID issueId, String poiId, FieldTaskStatus status, UserRef assignee,
                           Verdict verdict, String fieldRevisionId, String finalRevisionId, String reason,
                           String disputeReason, Instant createdAt, Instant assignedAt, Instant decidedAt) {}

    /**
     * 错误单详情：版本内容、处理历史、异议与现场任务，以及当前用户可执行的动作。
     * {@code viewerRole} 为 {@code COLLECTOR}、{@code REVIEWER}、{@code FIELD_WORKER} 或 {@code ADMIN}。
     */
    public record IssueDetail(IssueView issue, PoiRevision flaggedRevision, PoiRevision currentRevision,
                              PoiRevision finalRevision, List<EventView> events, DisputeView dispute, TaskView task,
                              String viewerRole, Set<IssueAction> allowedActions) {}

    public record ReviewHistory(String poiId, List<ReviewRecordView> reviews, List<IssueView> issues) {}

    public record Page<T>(List<T> items, int page, int size) {}
}
