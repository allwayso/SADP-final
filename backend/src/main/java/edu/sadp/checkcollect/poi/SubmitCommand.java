package edu.sadp.checkcollect.poi;

import java.time.Instant;
import java.util.List;
import org.springframework.web.multipart.MultipartFile;

/**
 * 采集提交命令：位置与定位时间必须由设备提供，缺任意一项都不允许提交。
 * FIELD_CHECK 的 sourceTaskId 由 M3 传入，M2 只保存不判定。
 */
public record SubmitCommand(
        PoiCategory category,
        String name,
        String description,
        double lon,
        double lat,
        String crs,
        Instant locatedAt,
        Double accuracyMeters,
        List<MultipartFile> photos) {}
