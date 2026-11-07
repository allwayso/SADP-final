package edu.sadp.checkcollect.poi;

import java.time.Instant;
import java.util.UUID;

/** 地图查询与列表展示用的 POI 摘要，以当前有效版本加 M3 同步的核验摘要为准。 */
public record PoiSummary(
        UUID id,
        UUID currentRevisionId,
        PoiCategory category,
        String categoryLabel,
        String name,
        String description,
        double lon,
        double lat,
        String crs,
        int revisionNo,
        long revisionCount,
        Instant createdAt,
        Instant updatedAt,
        ReviewSummary review) {}
