package edu.sadp.checkcollect.review;

import java.time.Instant;
import java.util.UUID;

/** M3 持久化记录。只在模块内部使用，对外通过 {@link ReviewViews} 中的 DTO 交换。 */
public final class ReviewModels {
    private ReviewModels() {}

    public enum Outcome { PASSED, ISSUE }

    public record ReviewRecord(UUID id, String poiId, String revisionId, UUID reviewerId, Outcome outcome,
                               UUID issueId, Instant createdAt) {}

    public record ReviewIssue(UUID id, String poiId, String flaggedRevisionId, String currentRevisionId,
                              UUID collectorId, UUID reviewerId, String description, IssueStatus status,
                              String finalRevisionId, Instant createdAt, Instant updatedAt, Instant closedAt) {
        public boolean isParty(UUID userId) {
            return collectorId.equals(userId) || reviewerId.equals(userId);
        }
    }

    public record IssueEvent(UUID id, UUID issueId, UUID actorId, IssueAction action, IssueStatus fromStatus,
                             IssueStatus toStatus, String revisionId, String note, Instant createdAt) {}

    public record Dispute(UUID id, UUID issueId, UUID submittedBy, String reason, String status,
                          Instant createdAt, Instant decidedAt) {}

    public record FieldTask(UUID id, UUID disputeId, UUID issueId, UUID assigneeId, FieldTaskStatus status,
                            Verdict verdict, String fieldRevisionId, String finalRevisionId, String reason,
                            Instant createdAt, Instant assignedAt, Instant decidedAt) {}
}
