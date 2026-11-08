package edu.sadp.checkcollect.review;

import edu.sadp.checkcollect.review.ReviewModels.Dispute;
import edu.sadp.checkcollect.review.ReviewModels.FieldTask;
import edu.sadp.checkcollect.review.ReviewModels.IssueEvent;
import edu.sadp.checkcollect.review.ReviewModels.Outcome;
import edu.sadp.checkcollect.review.ReviewModels.ReviewIssue;
import edu.sadp.checkcollect.review.ReviewModels.ReviewRecord;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** M3 数据访问。只读写 M3 自己的 review_* 表，时间由数据库服务器生成。 */
@Repository
public class ReviewRepository {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    public ReviewRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        return value == null ? null : Enum.valueOf(type, value);
    }

    private static ReviewRecord mapRecord(ResultSet rs, int row) throws SQLException {
        return new ReviewRecord(rs.getObject("id", UUID.class), rs.getString("poi_id"), rs.getString("revision_id"),
                rs.getObject("reviewer_id", UUID.class), Outcome.valueOf(rs.getString("outcome")),
                rs.getObject("issue_id", UUID.class), instant(rs, "created_at"));
    }

    private static ReviewIssue mapIssue(ResultSet rs, int row) throws SQLException {
        return new ReviewIssue(rs.getObject("id", UUID.class), rs.getString("poi_id"), rs.getString("flagged_revision_id"),
                rs.getString("current_revision_id"), rs.getObject("collector_id", UUID.class),
                rs.getObject("reviewer_id", UUID.class), rs.getString("description"),
                IssueStatus.valueOf(rs.getString("status")), rs.getString("final_revision_id"),
                instant(rs, "created_at"), instant(rs, "updated_at"), instant(rs, "closed_at"));
    }

    private static IssueEvent mapEvent(ResultSet rs, int row) throws SQLException {
        return new IssueEvent(rs.getObject("id", UUID.class), rs.getObject("issue_id", UUID.class),
                rs.getObject("actor_id", UUID.class), IssueAction.valueOf(rs.getString("action")),
                enumValue(IssueStatus.class, rs.getString("from_status")), IssueStatus.valueOf(rs.getString("to_status")),
                rs.getString("revision_id"), rs.getString("note"), instant(rs, "created_at"));
    }

    private static Dispute mapDispute(ResultSet rs, int row) throws SQLException {
        return new Dispute(rs.getObject("id", UUID.class), rs.getObject("issue_id", UUID.class),
                rs.getObject("submitted_by", UUID.class), rs.getString("reason"), rs.getString("status"),
                instant(rs, "created_at"), instant(rs, "decided_at"));
    }

    private static FieldTask mapTask(ResultSet rs, int row) throws SQLException {
        return new FieldTask(rs.getObject("id", UUID.class), rs.getObject("dispute_id", UUID.class),
                rs.getObject("issue_id", UUID.class), rs.getObject("assignee_id", UUID.class),
                FieldTaskStatus.valueOf(rs.getString("status")), enumValue(Verdict.class, rs.getString("verdict")),
                rs.getString("field_revision_id"), rs.getString("final_revision_id"), rs.getString("reason"),
                instant(rs, "created_at"), instant(rs, "assigned_at"), instant(rs, "decided_at"));
    }

    // ---- 核验记录 ----

    public void insertRecord(UUID id, String poiId, String revisionId, UUID reviewerId, Outcome outcome, UUID issueId) {
        jdbc.update("INSERT INTO review_records(id, poi_id, revision_id, reviewer_id, outcome, issue_id) VALUES (?, ?, ?, ?, ?, ?)",
                id, poiId, revisionId, reviewerId, outcome.name(), issueId);
    }

    public Optional<ReviewRecord> findRecordByRevision(String revisionId) {
        return jdbc.query("SELECT * FROM review_records WHERE revision_id = ?", ReviewRepository::mapRecord, revisionId)
                .stream().findFirst();
    }

    public Set<String> reviewedRevisionIds(Collection<String> revisionIds) {
        if (revisionIds.isEmpty()) return Set.of();
        return Set.copyOf(named.queryForList("SELECT revision_id FROM review_records WHERE revision_id IN (:ids)",
                new MapSqlParameterSource("ids", revisionIds), String.class));
    }

    public List<ReviewRecord> recordsForPoi(String poiId) {
        return jdbc.query("SELECT * FROM review_records WHERE poi_id = ? ORDER BY created_at, id", ReviewRepository::mapRecord, poiId);
    }

    // ---- 错误单 ----

    public void insertIssue(UUID id, String poiId, String revisionId, UUID collectorId, UUID reviewerId, String description) {
        jdbc.update("""
                INSERT INTO review_issues(id, poi_id, flagged_revision_id, current_revision_id, collector_id, reviewer_id, description, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'OPEN')""", id, poiId, revisionId, revisionId, collectorId, reviewerId, description);
    }

    public Optional<ReviewIssue> findIssue(UUID id) {
        return jdbc.query("SELECT * FROM review_issues WHERE id = ?", ReviewRepository::mapIssue, id).stream().findFirst();
    }

    /** 读取并对错误单加行锁，防止并发操作越过状态检查。 */
    public Optional<ReviewIssue> lockIssue(UUID id) {
        return jdbc.query("SELECT * FROM review_issues WHERE id = ? FOR UPDATE", ReviewRepository::mapIssue, id).stream().findFirst();
    }

    public Optional<ReviewIssue> findActiveIssueForPoi(String poiId) {
        return jdbc.query("SELECT * FROM review_issues WHERE poi_id = ? AND status NOT IN ('RESOLVED', 'CLOSED')",
                ReviewRepository::mapIssue, poiId).stream().findFirst();
    }

    public Set<String> poisWithActiveIssue(Collection<String> poiIds) {
        if (poiIds.isEmpty()) return Set.of();
        return Set.copyOf(named.queryForList("SELECT poi_id FROM review_issues WHERE status NOT IN ('RESOLVED', 'CLOSED') AND poi_id IN (:ids)",
                new MapSqlParameterSource("ids", poiIds), String.class));
    }

    /**
     * 按参与人与状态筛选错误单。{@code userId} 为空时不按参与人筛选（管理员视图）。
     * 第三方工作人员通过其分配任务看到对应错误单。
     */
    public List<ReviewIssue> listIssues(UUID userId, Collection<IssueStatus> statuses, String poiId, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT i.* FROM review_issues i WHERE 1 = 1");
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (userId != null) {
            sql.append(" AND (i.collector_id = :user OR i.reviewer_id = :user OR EXISTS (SELECT 1 FROM review_field_tasks t WHERE t.issue_id = i.id AND t.assignee_id = :user))");
            params.addValue("user", userId);
        }
        if (statuses != null && !statuses.isEmpty()) {
            sql.append(" AND i.status IN (:statuses)");
            params.addValue("statuses", statuses.stream().map(Enum::name).toList());
        }
        if (poiId != null) {
            sql.append(" AND i.poi_id = :poi");
            params.addValue("poi", poiId);
        }
        sql.append(" ORDER BY i.updated_at DESC, i.id LIMIT :limit OFFSET :offset");
        params.addValue("limit", limit).addValue("offset", offset);
        return named.query(sql.toString(), params, ReviewRepository::mapIssue);
    }

    public void updateIssueStatus(UUID id, IssueStatus status, String currentRevisionId) {
        boolean closing = !status.active();
        jdbc.update("UPDATE review_issues SET status = ?, current_revision_id = ?, updated_at = NOW(), closed_at = CASE WHEN ? THEN NOW() ELSE NULL END WHERE id = ?",
                status.name(), currentRevisionId, closing, id);
    }

    public void closeIssueWithFinal(UUID id, String finalRevisionId) {
        jdbc.update("UPDATE review_issues SET status = 'CLOSED', final_revision_id = ?, updated_at = NOW(), closed_at = NOW() WHERE id = ?",
                finalRevisionId, id);
    }

    public void touchIssue(UUID id) {
        jdbc.update("UPDATE review_issues SET updated_at = NOW() WHERE id = ?", id);
    }

    // ---- 处理历史 ----

    public void insertEvent(UUID issueId, UUID actorId, IssueAction action, IssueStatus from, IssueStatus to, String revisionId, String note) {
        jdbc.update("INSERT INTO review_issue_events(id, issue_id, actor_id, action, from_status, to_status, revision_id, note) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), issueId, actorId, action.name(), from == null ? null : from.name(), to.name(), revisionId, note);
    }

    public List<IssueEvent> events(UUID issueId) {
        return jdbc.query("SELECT * FROM review_issue_events WHERE issue_id = ? ORDER BY seq", ReviewRepository::mapEvent, issueId);
    }

    // ---- 异议与现场任务 ----

    public void insertDispute(UUID id, UUID issueId, UUID submittedBy, String reason) {
        jdbc.update("INSERT INTO review_disputes(id, issue_id, submitted_by, reason, status) VALUES (?, ?, ?, ?, 'OPEN')",
                id, issueId, submittedBy, reason);
    }

    public Optional<Dispute> findDisputeByIssue(UUID issueId) {
        return jdbc.query("SELECT * FROM review_disputes WHERE issue_id = ?", ReviewRepository::mapDispute, issueId).stream().findFirst();
    }

    public Optional<Dispute> findDispute(UUID id) {
        return jdbc.query("SELECT * FROM review_disputes WHERE id = ?", ReviewRepository::mapDispute, id).stream().findFirst();
    }

    public List<Dispute> listDisputes(UUID userId, int limit, int offset) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("limit", limit).addValue("offset", offset);
        String filter = "";
        if (userId != null) {
            filter = " WHERE i.collector_id = :user OR i.reviewer_id = :user OR t.assignee_id = :user";
            params.addValue("user", userId);
        }
        return named.query("SELECT d.* FROM review_disputes d JOIN review_issues i ON i.id = d.issue_id LEFT JOIN review_field_tasks t ON t.dispute_id = d.id"
                + filter + " ORDER BY d.created_at DESC, d.id LIMIT :limit OFFSET :offset", params, ReviewRepository::mapDispute);
    }

    public void markDisputeDecided(UUID id) {
        jdbc.update("UPDATE review_disputes SET status = 'DECIDED', decided_at = NOW() WHERE id = ?", id);
    }

    public void insertTask(UUID id, UUID disputeId, UUID issueId, UUID assigneeId) {
        jdbc.update("INSERT INTO review_field_tasks(id, dispute_id, issue_id, assignee_id, status, assigned_at) VALUES (?, ?, ?, ?, ?, CASE WHEN ? THEN NOW() END)",
                id, disputeId, issueId, assigneeId, assigneeId == null ? "UNASSIGNED" : "ASSIGNED", assigneeId != null);
    }

    public Optional<FieldTask> findTask(UUID id) {
        return jdbc.query("SELECT * FROM review_field_tasks WHERE id = ?", ReviewRepository::mapTask, id).stream().findFirst();
    }

    public Optional<FieldTask> lockTask(UUID id) {
        return jdbc.query("SELECT * FROM review_field_tasks WHERE id = ? FOR UPDATE", ReviewRepository::mapTask, id).stream().findFirst();
    }

    public Optional<FieldTask> findTaskByIssue(UUID issueId) {
        return jdbc.query("SELECT * FROM review_field_tasks WHERE issue_id = ?", ReviewRepository::mapTask, issueId).stream().findFirst();
    }

    public List<FieldTask> listTasks(UUID assigneeId, Collection<FieldTaskStatus> statuses, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT * FROM review_field_tasks WHERE 1 = 1");
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (assigneeId != null) {
            sql.append(" AND assignee_id = :user");
            params.addValue("user", assigneeId);
        }
        if (statuses != null && !statuses.isEmpty()) {
            sql.append(" AND status IN (:statuses)");
            params.addValue("statuses", statuses.stream().map(Enum::name).toList());
        }
        sql.append(" ORDER BY created_at DESC, id LIMIT :limit OFFSET :offset");
        params.addValue("limit", limit).addValue("offset", offset);
        return named.query(sql.toString(), params, ReviewRepository::mapTask);
    }

    public List<FieldTask> unassignedTasks() {
        return jdbc.query("SELECT * FROM review_field_tasks WHERE status = 'UNASSIGNED' ORDER BY created_at, id", ReviewRepository::mapTask);
    }

    /** 每位第三方人员当前未完成任务数，用于简单的负载均衡选择。 */
    public long openTaskCount(UUID assigneeId) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM review_field_tasks WHERE assignee_id = ? AND status = 'ASSIGNED'", Long.class, assigneeId);
        return count == null ? 0 : count;
    }

    public void assignTask(UUID id, UUID assigneeId) {
        jdbc.update("UPDATE review_field_tasks SET assignee_id = ?, status = 'ASSIGNED', assigned_at = NOW() WHERE id = ?", assigneeId, id);
    }

    public void setFieldRevision(UUID id, String revisionId) {
        jdbc.update("UPDATE review_field_tasks SET field_revision_id = ? WHERE id = ?", revisionId, id);
    }

    public void completeTask(UUID id, Verdict verdict, String finalRevisionId, String reason) {
        jdbc.update("UPDATE review_field_tasks SET status = 'COMPLETED', verdict = ?, final_revision_id = ?, reason = ?, decided_at = NOW() WHERE id = ?",
                verdict.name(), finalRevisionId, reason, id);
    }
}
