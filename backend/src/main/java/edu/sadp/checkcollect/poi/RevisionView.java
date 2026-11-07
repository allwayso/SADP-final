package edu.sadp.checkcollect.poi;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 一次采集版本的完整内容，一经写入不再修改。
 * M3 通过 {@code PoiService.getRevision(id)} 读取，核验必须明确针对某个版本。
 */
public record RevisionView(
        UUID id,
        UUID poiId,
        int revisionNo,
        SubmitPurpose purpose,
        PoiCategory category,
        String name,
        String description,
        double lon,
        double lat,
        String crs,
        Instant locatedAt,
        Double accuracyMeters,
        UUID sourceTaskId,
        UUID submittedBy,
        Instant submittedAt,
        List<PhotoView> photos) {}
