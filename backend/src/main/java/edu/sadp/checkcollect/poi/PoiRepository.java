package edu.sadp.checkcollect.poi;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * M2 数据访问。只写 V2__poi.sql 中属于本模块的四张表，不读取其他模块的业务表。
 * poi_review_summaries 只由 M3 通过 {@link PoiService#setReviewSummary} 触发写入。
 */
@Repository
public class PoiRepository {

    private static final String SELECT_SUMMARY = """
            SELECT p.id, p.current_revision_id, p.created_at, p.updated_at,
                   r.revision_no, r.category, r.name, r.description, r.lon, r.lat, r.crs,
                   (SELECT count(*) FROM poi_revisions c WHERE c.poi_id = p.id) AS revision_count,
                   s.state, s.progress, s.error_flag, s.summary, s.updated_at AS review_updated_at
            FROM pois p
            JOIN poi_revisions r ON r.id = p.current_revision_id
            LEFT JOIN poi_review_summaries s ON s.poi_id = p.id
            """;

    private static final String FROM_SUMMARY = """
            FROM pois p
            JOIN poi_revisions r ON r.id = p.current_revision_id
            LEFT JOIN poi_review_summaries s ON s.poi_id = p.id
            """;

    private final JdbcTemplate jdbc;

    public PoiRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void insertPoi(UUID id, UUID createdBy) {
        jdbc.update("INSERT INTO pois (id, created_by) VALUES (?, ?)", id, createdBy);
    }

    public Optional<PoiRow> lockPoi(UUID poiId) {
        return jdbc.query("SELECT id, current_revision_id, created_by FROM pois WHERE id = ? AND deleted_at IS NULL FOR UPDATE",
                (rs, row) -> new PoiRow(rs.getObject("id", UUID.class),
                        rs.getObject("current_revision_id", UUID.class),
                        rs.getObject("created_by", UUID.class)),
                poiId).stream().findFirst();
    }

    public Optional<PoiRow> findPoi(UUID poiId) {
        return jdbc.query("SELECT id, current_revision_id, created_by FROM pois WHERE id = ? AND deleted_at IS NULL",
                (rs, row) -> new PoiRow(rs.getObject("id", UUID.class),
                        rs.getObject("current_revision_id", UUID.class),
                        rs.getObject("created_by", UUID.class)),
                poiId).stream().findFirst();
    }

    public int nextRevisionNo(UUID poiId) {
        Integer next = jdbc.queryForObject(
                "SELECT COALESCE(MAX(revision_no), 0) + 1 FROM poi_revisions WHERE poi_id = ?", Integer.class, poiId);
        return next == null ? 1 : next;
    }

    public void insertRevision(UUID id, UUID poiId, int revisionNo, SubmitCommand command, SubmitPurpose purpose,
                               UUID sourceTaskId, UUID submittedBy) {
        jdbc.update("""
                INSERT INTO poi_revisions (id, poi_id, revision_no, purpose, category, name, description,
                       lon, lat, crs, located_at, accuracy_m, source_task_id, submitted_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, poiId, revisionNo, purpose.name(), command.category().name(),
                command.name().trim(), command.description() == null ? "" : command.description().trim(),
                command.lon(), command.lat(), command.crs(), Timestamp.from(command.locatedAt()),
                command.accuracyMeters(), sourceTaskId, submittedBy);
    }

    public void updateCurrentRevision(UUID poiId, UUID revisionId) {
        jdbc.update("UPDATE pois SET current_revision_id = ?, updated_at = NOW() WHERE id = ?", revisionId, poiId);
    }

    public void insertPhoto(UUID id, UUID revisionId, PhotoStorage.StoredPhoto photo, String ocrText) {
        jdbc.update("""
                INSERT INTO poi_photos (id, revision_id, stored_path, content_type, byte_size, ocr_text)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                id, revisionId, photo.relativePath(), photo.contentType(), photo.byteSize(),
                ocrText == null || ocrText.isBlank() ? null : ocrText);
    }

    public Optional<PhotoRow> findPhoto(UUID poiId, UUID photoId) {
        return jdbc.query("""
                SELECT ph.id, ph.revision_id, ph.stored_path, ph.content_type, ph.byte_size, ph.ocr_text
                FROM poi_photos ph
                JOIN poi_revisions r ON r.id = ph.revision_id
                WHERE ph.id = ? AND r.poi_id = ?
                """,
                (rs, row) -> mapPhotoRow(rs), photoId, poiId).stream().findFirst();
    }

    public Optional<PhotoRow> findPhoto(UUID photoId) {
        return jdbc.query("SELECT id, revision_id, stored_path, content_type, byte_size, ocr_text FROM poi_photos WHERE id = ?",
                (rs, row) -> mapPhotoRow(rs), photoId).stream().findFirst();
    }

    public List<PoiSummary> search(PoiCategory category, Double[] bbox, String progress, int size, int offset) {
        List<Object> args = new ArrayList<>();
        String where = whereClause(category, bbox, progress, args);
        args.add(size);
        args.add(offset);
        return jdbc.query(SELECT_SUMMARY + where + " ORDER BY p.updated_at DESC, p.id LIMIT ? OFFSET ?",
                this::mapSummary, args.toArray());
    }

    public long count(PoiCategory category, Double[] bbox, String progress) {
        List<Object> args = new ArrayList<>();
        String where = whereClause(category, bbox, progress, args);
        Long total = jdbc.queryForObject("SELECT count(*) " + FROM_SUMMARY + where, Long.class, args.toArray());
        return total == null ? 0 : total;
    }

    public Optional<PoiSummary> findSummary(UUID poiId) {
        return jdbc.query(SELECT_SUMMARY + " WHERE p.deleted_at IS NULL AND p.id = ?", this::mapSummary, poiId)
                .stream().findFirst();
    }

    public List<RevisionView> revisions(UUID poiId) {
        List<RevisionView> revisions = jdbc.query("""
                        SELECT * FROM poi_revisions WHERE poi_id = ? ORDER BY revision_no DESC
                        """,
                (rs, row) -> mapRevision(rs, List.of()), poiId);
        Map<UUID, List<PhotoView>> photos = photosOfPoi(poiId);
        return revisions.stream()
                .map(revision -> new RevisionView(revision.id(), revision.poiId(), revision.revisionNo(),
                        revision.purpose(), revision.category(), revision.name(), revision.description(),
                        revision.lon(), revision.lat(), revision.crs(), revision.locatedAt(),
                        revision.accuracyMeters(), revision.sourceTaskId(), revision.submittedBy(),
                        revision.submittedAt(), photos.getOrDefault(revision.id(), List.of())))
                .toList();
    }

    public Optional<RevisionView> findRevision(UUID revisionId) {
        return jdbc.query("SELECT * FROM poi_revisions WHERE id = ?",
                (rs, row) -> mapRevision(rs, photosOfRevision(rs.getObject("id", UUID.class))),
                revisionId).stream().findFirst();
    }

    public boolean revisionBelongsTo(UUID revisionId, UUID poiId) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM poi_revisions WHERE id = ? AND poi_id = ?)", Boolean.class, revisionId, poiId);
        return Boolean.TRUE.equals(exists);
    }

    public void upsertReviewSummary(UUID poiId, UUID revisionId, ReviewSummary summary, UUID updatedBy) {
        jdbc.update("""
                INSERT INTO poi_review_summaries (poi_id, revision_id, state, progress, error_flag, summary, updated_by, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, NOW())
                ON CONFLICT (poi_id) DO UPDATE SET
                  revision_id = EXCLUDED.revision_id,
                  state = EXCLUDED.state,
                  progress = EXCLUDED.progress,
                  error_flag = EXCLUDED.error_flag,
                  summary = EXCLUDED.summary,
                  updated_by = EXCLUDED.updated_by,
                  updated_at = NOW()
                """,
                poiId, revisionId, summary.state(), summary.progress(), summary.errorFlag(), summary.text(), updatedBy);
        jdbc.update("UPDATE pois SET updated_at = NOW() WHERE id = ?", poiId);
    }

    private String whereClause(PoiCategory category, Double[] bbox, String progress, List<Object> args) {
        StringBuilder where = new StringBuilder(" WHERE p.deleted_at IS NULL");
        if (category != null) {
            where.append(" AND r.category = ?");
            args.add(category.name());
        }
        if (bbox != null) {
            where.append(" AND r.lon BETWEEN ? AND ? AND r.lat BETWEEN ? AND ?");
            args.add(bbox[0]);
            args.add(bbox[2]);
            args.add(bbox[1]);
            args.add(bbox[3]);
        }
        if (progress != null && !progress.isBlank()) {
            String value = progress.trim().toUpperCase(Locale.ROOT);
            if (value.equals("NONE")) {
                where.append(" AND s.progress IS NULL");
            } else {
                where.append(" AND s.progress = ?");
                args.add(progress.trim());
            }
        }
        return where.toString();
    }

    private Map<UUID, List<PhotoView>> photosOfPoi(UUID poiId) {
        return jdbc.query("""
                        SELECT ph.id, ph.revision_id, ph.content_type, ph.byte_size, ph.ocr_text
                        FROM poi_photos ph
                        JOIN poi_revisions r ON r.id = ph.revision_id
                        WHERE r.poi_id = ?
                        ORDER BY ph.created_at
                        """,
                (rs, row) -> new PhotoRow(rs.getObject("id", UUID.class), rs.getObject("revision_id", UUID.class),
                        null, rs.getString("content_type"), rs.getLong("byte_size"), rs.getString("ocr_text")),
                poiId).stream().collect(Collectors.groupingBy(PhotoRow::revisionId,
                Collectors.mapping(row -> new PhotoView(row.id(), row.contentType(), row.byteSize(), row.ocrText()),
                        Collectors.toList())));
    }

    private List<PhotoView> photosOfRevision(UUID revisionId) {
        return jdbc.query("""
                        SELECT id, revision_id, content_type, byte_size, ocr_text
                        FROM poi_photos WHERE revision_id = ? ORDER BY created_at
                        """,
                (rs, row) -> new PhotoView(rs.getObject("id", UUID.class), rs.getString("content_type"),
                        rs.getLong("byte_size"), rs.getString("ocr_text")),
                revisionId);
    }

    private PoiSummary mapSummary(ResultSet rs) throws SQLException {
        Timestamp reviewUpdated = rs.getTimestamp("review_updated_at");
        ReviewSummary review = reviewUpdated == null ? null : new ReviewSummary(
                rs.getString("state"), rs.getString("progress"), rs.getBoolean("error_flag"),
                rs.getString("summary"), reviewUpdated.toInstant());
        PoiCategory category = PoiCategory.valueOf(rs.getString("category"));
        return new PoiSummary(
                rs.getObject("id", UUID.class),
                rs.getObject("current_revision_id", UUID.class),
                category,
                category.label(),
                rs.getString("name"),
                rs.getString("description"),
                rs.getDouble("lon"),
                rs.getDouble("lat"),
                rs.getString("crs"),
                rs.getInt("revision_no"),
                rs.getLong("revision_count"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                review);
    }

    private RevisionView mapRevision(ResultSet rs, List<PhotoView> photos) throws SQLException {
        return new RevisionView(
                rs.getObject("id", UUID.class),
                rs.getObject("poi_id", UUID.class),
                rs.getInt("revision_no"),
                SubmitPurpose.valueOf(rs.getString("purpose")),
                PoiCategory.valueOf(rs.getString("category")),
                rs.getString("name"),
                rs.getString("description"),
                rs.getDouble("lon"),
                rs.getDouble("lat"),
                rs.getString("crs"),
                rs.getTimestamp("located_at").toInstant(),
                rs.getObject("accuracy_m", Double.class),
                rs.getObject("source_task_id", UUID.class),
                rs.getObject("submitted_by", UUID.class),
                rs.getTimestamp("submitted_at").toInstant(),
                photos);
    }

    private PhotoRow mapPhotoRow(ResultSet rs) throws SQLException {
        return new PhotoRow(rs.getObject("id", UUID.class), rs.getObject("revision_id", UUID.class),
                rs.getString("stored_path"), rs.getString("content_type"), rs.getLong("byte_size"),
                rs.getString("ocr_text"));
    }

    public record PoiRow(UUID id, UUID currentRevisionId, UUID createdBy) {}

    public record PhotoRow(UUID id, UUID revisionId, String storedPath, String contentType, long byteSize, String ocrText) {}
}
