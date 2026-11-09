package edu.sadp.checkcollect.poi;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * M2 HTTP 边界。路径与表二约定一致；只做参数绑定与格式检查，业务规则在 PoiService。
 * 不改用 @RequestBody：照片与位置、分类、描述必须同一次 multipart 提交，避免照片与位置分离。
 */
@RestController
@RequestMapping("/api/pois")
public class PoiController {

    private final PoiService pois;

    public PoiController(PoiService pois) { this.pois = pois; }

    @GetMapping
    public PoiPage search(@RequestParam(required = false) String category,
                          @RequestParam(required = false) String bbox,
                          @RequestParam(required = false) String progress,
                          @RequestParam(defaultValue = "0") int page,
                          @RequestParam(defaultValue = "50") int size) {
        return pois.search(PoiCategory.parseOptional(category), parseBbox(bbox), progress, page, size);
    }

    @GetMapping("/{poiId}")
    public PoiDetail detail(@PathVariable UUID poiId) {
        return pois.detail(poiId);
    }

    @PostMapping
    public RevisionRef create(@RequestParam(required = false) String category,
                              @RequestParam(required = false) String name,
                              @RequestParam(required = false) String description,
                              @RequestParam(required = false) Double lon,
                              @RequestParam(required = false) Double lat,
                              @RequestParam(required = false) String crs,
                              @RequestParam(required = false) String locatedAt,
                              @RequestParam(required = false) Double accuracyMeters,
                              @RequestParam(name = "photos", required = false) List<MultipartFile> photos) {
        return pois.submitNew(command(category, name, description, lon, lat, crs, locatedAt, accuracyMeters, photos));
    }

    @PostMapping("/{poiId}/revisions")
    public RevisionRef revise(@PathVariable UUID poiId,
                              @RequestParam UUID expectedRevisionId,
                              @RequestParam(required = false) String category,
                              @RequestParam(required = false) String name,
                              @RequestParam(required = false) String description,
                              @RequestParam(required = false) Double lon,
                              @RequestParam(required = false) Double lat,
                              @RequestParam(required = false) String crs,
                              @RequestParam(required = false) String locatedAt,
                              @RequestParam(required = false) Double accuracyMeters,
                              @RequestParam(name = "photos", required = false) List<MultipartFile> photos) {
        return pois.submitCorrection(poiId,
                command(category, name, description, lon, lat, crs, locatedAt, accuracyMeters, photos), expectedRevisionId);
    }

    @PostMapping("/ocr")
    public OcrAdapter.OcrSuggestion ocr(@RequestParam(name = "photo", required = false) MultipartFile photo) {
        return pois.suggestCategory(photo);
    }

    @GetMapping("/{poiId}/photos/{photoId}")
    public ResponseEntity<Resource> photo(@PathVariable UUID poiId, @PathVariable UUID photoId) {
        PoiService.PhotoContent content = pois.photo(poiId, photoId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.contentType()))
                .cacheControl(CacheControl.noCache())
                .body(content.resource());
    }

    @GetMapping("/{poiId}/navigation")
    public NavigationTarget navigation(@PathVariable UUID poiId,
                                       @RequestParam(required = false) Double fromLon,
                                       @RequestParam(required = false) Double fromLat) {
        return pois.navigation(poiId, fromLon, fromLat);
    }

    private static SubmitCommand command(String category, String name, String description, Double lon, Double lat,
                                         String crs, String locatedAt, Double accuracyMeters,
                                         List<MultipartFile> photos) {
        if (lon == null || lat == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少拍照位置：请允许设备定位后重新采集，位置不能手动填写");
        }
        if (crs == null || crs.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少坐标系标识，请由设备重新定位");
        }
        if (locatedAt == null || locatedAt.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少拍照定位时间，请由设备重新定位");
        }
        Instant located;
        try {
            located = Instant.parse(locatedAt.trim());
        } catch (DateTimeParseException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "定位时间格式不正确，应为 ISO-8601 时间");
        }
        return new SubmitCommand(PoiCategory.parse(category), name, description, lon, lat, crs.trim(), located,
                accuracyMeters, photos == null ? List.of() : photos);
    }

    private static Double[] parseBbox(String bbox) {
        if (bbox == null || bbox.isBlank()) return null;
        String[] parts = bbox.split(",");
        if (parts.length != 4) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "bbox 需要 minLon,minLat,maxLon,maxLat 四个数值");
        }
        try {
            double minLon = Double.parseDouble(parts[0].trim());
            double minLat = Double.parseDouble(parts[1].trim());
            double maxLon = Double.parseDouble(parts[2].trim());
            double maxLat = Double.parseDouble(parts[3].trim());
            if (minLon > maxLon || minLat > maxLat) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "bbox 范围不合法：最小值不能大于最大值");
            }
            return new Double[] {minLon, minLat, maxLon, maxLat};
        } catch (NumberFormatException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "bbox 需要 minLon,minLat,maxLon,maxLat 四个数值");
        }
    }
}
