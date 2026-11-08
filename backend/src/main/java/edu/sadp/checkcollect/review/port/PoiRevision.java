package edu.sadp.checkcollect.review.port;

import java.time.Instant;
import java.util.UUID;

/** M2 提供的采集版本只读视图。位置为设备感知的拍照位置，保留原始坐标系标识。 */
public record PoiRevision(
        String id,
        String poiId,
        int version,
        RevisionPurpose purpose,
        UUID submittedBy,
        String category,
        String name,
        String description,
        double latitude,
        double longitude,
        Double accuracyMeters,
        String coordinateSystem,
        Instant locatedAt,
        String photoId,
        Instant createdAt) {
}
