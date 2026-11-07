package edu.sadp.checkcollect.poi;

import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** POI 分类。取值同时受 V2__poi.sql 中 poi_revisions.category 的 CHECK 约束。 */
public enum PoiCategory {
    HOUSE("房屋"),
    SHOP("商铺"),
    BUS_STOP("公交站"),
    PARK("花坛绿地"),
    TREE("树木"),
    SCENIC("景点"),
    OTHER("其他");

    private final String label;

    PoiCategory(String label) { this.label = label; }

    public String label() { return label; }

    public static PoiCategory parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择 POI 分类");
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知的 POI 分类：" + raw);
        }
    }

    public static PoiCategory parseOptional(String raw) {
        return raw == null || raw.isBlank() ? null : parse(raw);
    }
}
