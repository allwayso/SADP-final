package edu.sadp.checkcollect.review.port;

import java.time.Instant;
import java.util.UUID;

/**
 * 请求 M2 创建采集版本的内容。{@code fieldTaskId} 仅在 {@link RevisionPurpose#FIELD_CHECK} 时提供，
 * 表示 M3 已确认该第三方人员持有此任务。
 */
public record RevisionDraft(
        String poiId,
        RevisionPurpose purpose,
        UUID submittedBy,
        String fieldTaskId,
        String category,
        String name,
        String description,
        double latitude,
        double longitude,
        Double accuracyMeters,
        String coordinateSystem,
        Instant locatedAt,
        String photoId) {
}
