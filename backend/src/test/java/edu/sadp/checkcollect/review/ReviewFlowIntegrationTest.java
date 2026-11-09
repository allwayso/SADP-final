package edu.sadp.checkcollect.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import edu.sadp.checkcollect.review.port.PoiRevision;
import edu.sadp.checkcollect.review.port.ReviewProgress;
import edu.sadp.checkcollect.review.port.RevisionDraft;
import edu.sadp.checkcollect.review.port.RevisionPurpose;
import edu.sadp.checkcollect.review.stub.InMemoryPoiRevisionStub;
import edu.sadp.checkcollect.review.stub.RecordingNotificationStub;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * M3 端到端集成测试：真实 PostgreSQL（嵌入式，Flyway 迁移真实执行）+ 真实 Spring Security，
 * 只把 M2、M4 换成桩。覆盖核验、错误单状态机、异议派单、第三方裁定、权限与失败分支。
 *
 * <p>测试数据使用各自唯一的 POI 标识，避免用例之间相互影响；数据库为一次性实例。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = {
        "app.review.demo-data=false",
        "app.review.poi-stub=true",
        "app.review.notification-stub=true",
        "app.messaging.transport=in-process",
        "APP_SEED_DEMO=true",
        "DEMO_PASSWORD=review-integration-pass",
        "INITIAL_ADMIN_USERNAME=integration-admin",
        "INITIAL_ADMIN_PASSWORD=integration-admin-pass",
})
class ReviewFlowIntegrationTest {
    private static final String DEMO_PASSWORD = "review-integration-pass";

    private static final EmbeddedPostgres POSTGRES;

    static {
        try {
            POSTGRES = EmbeddedPostgres.builder().start();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
    }

    @AfterAll
    static void stopPostgres() throws IOException {
        POSTGRES.close();
    }

    @Autowired
    private MockMvc mvc;

    private final JsonMapper json = JsonMapper.builder().build();

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private InMemoryPoiRevisionStub pois;

    @Autowired
    private RecordingNotificationStub notifications;

    private MockHttpSession admin;
    private MockHttpSession collector;
    private MockHttpSession reviewer;
    private MockHttpSession worker;
    private UUID workerId;

    @BeforeAll
    void signInDemoAccounts() throws Exception {
        // 演示账号首次登录必须改密；M1 已单独验证改密流程，这里直接标记为已激活
        jdbc.update("UPDATE app_users SET must_change_password = FALSE");
        admin = login("integration-admin", "integration-admin-pass");
        collector = login("member-b", DEMO_PASSWORD);
        reviewer = login("member-c", DEMO_PASSWORD);
        worker = login("member-d", DEMO_PASSWORD);
        workerId = userId("member-d");
    }

    // =====================================================================
    // 核验
    // =====================================================================

    @Test
    void reviewerSeesPendingRevisionAndPassesIt() throws Exception {
        String poiId = "poi-pass-" + UUID.randomUUID();
        PoiRevision revision = revision(poiId, "member-b");

        assertThat(pendingRevisionIds(reviewer)).contains(revision.id());

        JsonNode record = postJson("/api/reviews", reviewer,
                body("revisionId", revision.id(), "outcome", "PASSED"), 200);
        assertThat(record.get("outcome").asText()).isEqualTo("PASSED");
        assertThat(record.get("reviewer").get("displayName").asText()).isEqualTo("成员 C");
        assertThat(record.get("issueId").isNull()).isTrue();

        assertThat(pois.summary(poiId).orElseThrow().summary().progress()).isEqualTo(ReviewProgress.PASSED);
        assertThat(pendingRevisionIds(reviewer)).doesNotContain(revision.id());

        // 同一版本不能重复核验
        postJson("/api/reviews", reviewer, body("revisionId", revision.id(), "outcome", "PASSED"), 409);
    }

    @Test
    void issueLifecycleFromFlagToResolution() throws Exception {
        String poiId = "poi-issue-" + UUID.randomUUID();
        PoiRevision revision = revision(poiId, "member-b");

        JsonNode record = postJson("/api/reviews", reviewer,
                body("revisionId", revision.id(), "outcome", "ISSUE", "description", "招牌与分类不符，实际是文具店"),
                200);
        String issueId = record.get("issueId").asText();
        assertThat(record.get("outcome").asText()).isEqualTo("ISSUE");
        assertThat(pois.summary(poiId).orElseThrow().summary().progress()).isEqualTo(ReviewProgress.ISSUE_OPEN);

        // 核验者标识错误后点发原采集者
        RecordingNotificationStub.SentNotification reported = notification("REVIEW_ISSUE_REPORTED", issueId);
        assertThat(reported.target().userId()).isEqualTo(userId("member-b"));

        JsonNode detail = getJson("/api/issues/" + issueId, collector, 200);
        assertThat(detail.get("viewerRole").asText()).isEqualTo("COLLECTOR");
        assertThat(actions(detail)).contains("CORRECTED", "REJECTED", "NOTE");
        assertThat(detail.get("currentRevision").get("id").asText()).isEqualTo(revision.id());

        // 驳回必须说明理由
        postJson("/api/issues/" + issueId + "/rejections", collector, body("revisionId", revision.id()), 400);
        detail = postJson("/api/issues/" + issueId + "/rejections", collector,
                body("revisionId", revision.id(), "note", "现场招牌确为面馆，请复核"), 200);
        assertThat(detail.at("/issue/status").asText()).isEqualTo("DISPUTED");
        assertThat(pois.summary(poiId).orElseThrow().summary().progress()).isEqualTo(ReviewProgress.DISPUTED);
        assertThat(notification("REVIEW_ISSUE_REJECTED", issueId).target().userId()).isEqualTo(userId("member-c"));

        // 沟通后撤回驳回，回到待处理
        detail = postJson("/api/issues/" + issueId + "/resumptions", collector,
                body("revisionId", revision.id(), "note", "沟通后接受标识"), 200);
        assertThat(detail.at("/issue/status").asText()).isEqualTo("OPEN");

        // 整改：M3 经 M2 端口生成新版本，错误单进入待复核
        detail = postJson("/api/issues/" + issueId + "/revisions", collector,
                body("revisionId", revision.id(), "note", "重新拍摄招牌", "collection", collection("餐饮", "邻家面馆")), 200);
        String correctedRevisionId = detail.at("/issue/currentRevisionId").asText();
        assertThat(correctedRevisionId).isNotEqualTo(revision.id());
        assertThat(detail.at("/issue/status").asText()).isEqualTo("PENDING_RECHECK");
        assertThat(pois.getRevision(correctedRevisionId).orElseThrow().purpose()).isEqualTo(RevisionPurpose.CORRECTION);
        assertThat(pois.summary(poiId).orElseThrow().summary().progress()).isEqualTo(ReviewProgress.PENDING_RECHECK);
        assertThat(notification("REVIEW_ISSUE_CORRECTED", issueId).target().userId()).isEqualTo(userId("member-c"));

        // 过期版本不能再提交动作
        postJson("/api/issues/" + issueId + "/confirmations", reviewer, body("revisionId", revision.id()), 409);

        // 核验者确认整改通过
        detail = postJson("/api/issues/" + issueId + "/confirmations", reviewer,
                body("revisionId", correctedRevisionId, "note", "复核通过"), 200);
        assertThat(detail.at("/issue/status").asText()).isEqualTo("RESOLVED");
        assertThat(detail.at("/issue/closedAt").isNull()).isFalse();
        assertThat(pois.summary(poiId).orElseThrow().summary().progress()).isEqualTo(ReviewProgress.PASSED);
        assertThat(strings(detail.get("events"), "action")).contains("CREATED", "REJECTED", "RESUMED", "CORRECTED", "CONFIRMED");
        assertThat(notification("REVIEW_ISSUE_RESOLVED", issueId).target().userId()).isEqualTo(userId("member-b"));

        // 已结束的错误单不能再补充说明
        postJson("/api/issues/" + issueId + "/notes", collector, body("note", "还想补充一句"), 409);
        // 该 POI 已无未关闭错误单，新版本可以重新进入待检
        assertThat(pendingRevisionIds(reviewer)).doesNotContain(correctedRevisionId);
    }

    @Test
    void reviewerCanRequestChangesAndWithdrawIssue() throws Exception {
        String poiId = "poi-withdraw-" + UUID.randomUUID();
        PoiRevision revision = revision(poiId, "member-b");
        String issueId = postJson("/api/reviews", reviewer,
                body("revisionId", revision.id(), "outcome", "ISSUE", "description", "位置与描述不一致"), 200)
                .get("issueId").asText();

        // 采集者整改
        JsonNode corrected = postJson("/api/issues/" + issueId + "/revisions", collector,
                body("revisionId", revision.id(), "collection", collection("零售", "文具店")), 200);
        String correctedRevisionId = corrected.at("/issue/currentRevisionId").asText();

        // 复核不通过必须说明原因；退回后由采集者继续整改或驳回
        postJson("/api/issues/" + issueId + "/change-requests", reviewer, body("revisionId", correctedRevisionId), 400);
        JsonNode detail = postJson("/api/issues/" + issueId + "/change-requests", reviewer,
                body("revisionId", correctedRevisionId, "note", "照片仍看不清招牌"), 200);
        assertThat(detail.at("/issue/status").asText()).isEqualTo("OPEN");
        assertThat(actions(detail)).contains("WITHDRAWN");
        JsonNode collectorView = getJson("/api/issues/" + issueId, collector, 200);
        assertThat(actions(collectorView)).contains("CORRECTED", "REJECTED");

        // 经沟通核验者撤回错误标识，错误单关闭
        detail = postJson("/api/issues/" + issueId + "/withdrawals", reviewer,
                body("revisionId", correctedRevisionId, "note", "确认无误，撤回标识"), 200);
        assertThat(detail.at("/issue/status").asText()).isEqualTo("CLOSED");
        assertThat(detail.at("/issue/finalRevisionId").asText()).isEqualTo(correctedRevisionId);
        assertThat(pois.summary(poiId).orElseThrow().summary().progress()).isEqualTo(ReviewProgress.PASSED);
        // 已确认的版本不会重新回到待核验列表
        assertThat(pendingRevisionIds(reviewer)).doesNotContain(correctedRevisionId);
    }

    // =====================================================================
    // 异议与第三方现场任务
    // =====================================================================

    @Test
    void disputeIsAssignedToFieldWorkerWhoDecidesWithFieldRevision() throws Exception {
        String poiId = "poi-dispute-" + UUID.randomUUID();
        PoiRevision revision = revision(poiId, "member-b");
        String issueId = postJson("/api/reviews", reviewer,
                body("revisionId", revision.id(), "outcome", "ISSUE", "description", "招牌文字与分类不符"), 200)
                .get("issueId").asText();
        postJson("/api/issues/" + issueId + "/rejections", collector,
                body("revisionId", revision.id(), "note", "招牌确为面馆"), 200);

        // 只有当事双方可以提交异议
        postJson("/api/disputes", worker, body("issueId", issueId, "revisionId", revision.id(), "reason", "与我无关"), 403);

        JsonNode dispute = postJson("/api/disputes", collector,
                body("issueId", issueId, "revisionId", revision.id(), "reason", "沟通后仍无法达成共识，请第三方现场复核"), 200);
        assertThat(dispute.get("status").asText()).isEqualTo("OPEN");
        assertThat(dispute.get("submittedBy").get("displayName").asText()).isEqualTo("成员 B");

        JsonNode detail = getJson("/api/issues/" + issueId, reviewer, 200);
        assertThat(detail.at("/issue/status").asText()).isEqualTo("IN_ARBITRATION");
        assertThat(detail.at("/task/status").asText()).isEqualTo("ASSIGNED");
        assertThat(detail.at("/task/assignee/id").asText()).isEqualTo(workerId.toString());
        assertThat(pois.summary(poiId).orElseThrow().summary().progress()).isEqualTo(ReviewProgress.IN_ARBITRATION);

        String taskId = detail.at("/task/id").asText();
        // 现场任务点发被指派的第三方，地址指向任务本页
        RecordingNotificationStub.SentNotification assigned = notification("REVIEW_FIELD_TASK_ASSIGNED", taskId);
        assertThat(assigned.target().userId()).isEqualTo(workerId);
        assertThat(assigned.reference().type()).isEqualTo("FIELD_TASK");
        assertThat(notification("REVIEW_DISPUTE_SUBMITTED", issueId).target().userId()).isEqualTo(userId("member-c"));

        assertThat(taskIds(worker)).contains(taskId);

        // 第三方以现场采集版本替换错误数据
        JsonNode task = postJson("/api/field-tasks/" + taskId + "/result", worker,
                body("verdict", "REPLACED", "reason", "现场招牌确为文具店，原分类错误", "collection", collection("零售", "文具店")),
                200);
        assertThat(task.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(task.get("verdict").asText()).isEqualTo("REPLACED");
        String fieldRevisionId = task.get("fieldRevisionId").asText();
        assertThat(task.get("finalRevisionId").asText()).isEqualTo(fieldRevisionId);
        assertThat(pois.getRevision(fieldRevisionId).orElseThrow().purpose()).isEqualTo(RevisionPurpose.FIELD_CHECK);

        JsonNode closed = getJson("/api/issues/" + issueId, collector, 200);
        assertThat(closed.at("/issue/status").asText()).isEqualTo("CLOSED");
        assertThat(closed.at("/issue/finalRevisionId").asText()).isEqualTo(fieldRevisionId);
        assertThat(closed.get("finalRevision").get("category").asText()).isEqualTo("零售");
        assertThat(pois.summary(poiId).orElseThrow().summary().progress()).isEqualTo(ReviewProgress.FINAL);

        // 最终裁定点发争议双方，且不可再次修改
        assertThat(notification("REVIEW_DISPUTE_DECIDED", issueId).target().userId()).isEqualTo(userId("member-b"));
        assertThat(countNotifications("REVIEW_DISPUTE_DECIDED", issueId)).isEqualTo(2);
        postJson("/api/field-tasks/" + taskId + "/result", worker,
                body("verdict", "ORIGINAL_CORRECT", "reason", "改主意了"), 409);
    }

    @Test
    void adminAssignsTaskWhenNoFieldWorkerIsAvailable() throws Exception {
        String poiId = "poi-unassigned-" + UUID.randomUUID();
        PoiRevision revision = revision(poiId, "member-b");
        String issueId = postJson("/api/reviews", reviewer,
                body("revisionId", revision.id(), "outcome", "ISSUE", "description", "门口台阶位置有误"), 200)
                .get("issueId").asText();
        postJson("/api/issues/" + issueId + "/rejections", collector,
                body("revisionId", revision.id(), "note", "位置无误"), 200);

        // 暂时取消唯一的第三方工作人员角色，模拟“无可用人员”，会话仍然有效
        jdbc.update("DELETE FROM user_roles WHERE user_id = ? AND role = 'FIELD_WORKER'", workerId);
        String taskId;
        try {
            JsonNode dispute = postJson("/api/disputes", collector,
                    body("issueId", issueId, "revisionId", revision.id(), "reason", "无共识，请现场复核"), 200);
            assertThat(dispute.get("status").asText()).isEqualTo("OPEN");

            JsonNode detail = getJson("/api/issues/" + issueId, admin, 200);
            assertThat(detail.at("/task/status").asText()).isEqualTo("UNASSIGNED");
            assertThat(detail.at("/task/assignee").isNull()).isTrue();
            taskId = detail.at("/task/id").asText();
            // 无可用人员时群发管理员，关联到具体任务
            RecordingNotificationStub.SentNotification unassigned = notification("REVIEW_FIELD_TASK_UNASSIGNED", taskId);
            assertThat(unassigned.target().role().name()).isEqualTo("ADMIN");

            // 管理员可查看全部任务；没有第三方角色的人不能提交结论
            assertThat(taskIds(admin, false)).contains(taskId);
            postJson("/api/field-tasks/" + taskId + "/result", worker,
                    body("verdict", "ORIGINAL_CORRECT", "reason", "擅自处理"), 403);
        } finally {
            jdbc.update("INSERT INTO user_roles(user_id, role) VALUES (?, 'FIELD_WORKER')", workerId);
        }

        // 恢复第三方人员后管理员指派
        JsonNode assigned = patchJson("/api/field-tasks/" + taskId, admin, body("assigneeId", workerId.toString()), 200);
        assertThat(assigned.get("status").asText()).isEqualTo("ASSIGNED");
        assertThat(assigned.get("assignee").get("id").asText()).isEqualTo(workerId.toString());

        // 第三方完成后，管理员不能再改派
        postJson("/api/field-tasks/" + taskId + "/result", worker,
                body("verdict", "ORIGINAL_CORRECT", "reason", "现场核实原数据无误", "collection", collection("SHOP", "现场证据")), 200);
        patchJson("/api/field-tasks/" + taskId, admin, body("assigneeId", workerId.toString()), 409);
    }

    // =====================================================================
    // 权限、校验与失败分支
    // =====================================================================

    @Test
    void rejectsUnauthorizedAndStaleOperations() throws Exception {
        String poiId = "poi-rules-" + UUID.randomUUID();
        PoiRevision first = revision(poiId, "member-b");

        // 未登录
        mvc.perform(get("/api/reviews/pending")).andExpect(status().isUnauthorized());
        // 缺少 CSRF 凭证的写请求
        mvc.perform(post("/api/reviews").session(reviewer).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body("revisionId", first.id(), "outcome", "PASSED"))))
                .andExpect(status().isForbidden());

        // 采集者不能核验
        postJson("/api/reviews", collector, body("revisionId", first.id(), "outcome", "PASSED"), 403);
        // 采集者不能查看待检列表
        getJson("/api/reviews/pending", collector, 403);

        // 产生更新的版本后，旧版本不能再核验
        PoiRevision second = revision(poiId, "member-b");
        postJson("/api/reviews", reviewer, body("revisionId", first.id(), "outcome", "PASSED"), 409);

        // 核验者不能核验本人提交的版本
        String ownPoi = "poi-own-" + UUID.randomUUID();
        PoiRevision ownRevision = revision(ownPoi, "member-c");
        postJson("/api/reviews", reviewer, body("revisionId", ownRevision.id(), "outcome", "PASSED"), 403);

        // 标识错误必须填写说明
        postJson("/api/reviews", reviewer, body("revisionId", second.id(), "outcome", "ISSUE"), 400);
        String issueId = postJson("/api/reviews", reviewer,
                body("revisionId", second.id(), "outcome", "ISSUE", "description", "坐标偏移约 30 米"), 200)
                .get("issueId").asText();

        // 无关第三方不能查看错误单
        getJson("/api/issues/" + issueId, worker, 403);
        // 非当事人不能操作
        postJson("/api/issues/" + issueId + "/confirmations", collector,
                body("revisionId", second.id(), "note", "越权确认"), 403);
        // 状态不允许的动作
        postJson("/api/issues/" + issueId + "/confirmations", reviewer, body("revisionId", second.id()), 409);
        // 同一 POI 的第二张未关闭错误单
        PoiRevision third = revision(poiId, "member-b");
        postJson("/api/reviews", reviewer,
                body("revisionId", third.id(), "outcome", "ISSUE", "description", "重复建单"), 409);
        // 不存在的错误单
        getJson("/api/issues/" + UUID.randomUUID(), reviewer, 404);
        // 分页参数校验
        getJson("/api/issues?size=0", reviewer, 400);
        getJson("/api/reviews/pending?page=-1", reviewer, 400);
    }

    @Test
    void invalidCollectionIsRejectedBeforeCreatingRevision() throws Exception {
        String poiId = "poi-collection-" + UUID.randomUUID();
        PoiRevision revision = revision(poiId, "member-b");
        String issueId = postJson("/api/reviews", reviewer,
                body("revisionId", revision.id(), "outcome", "ISSUE", "description", "照片模糊"), 200)
                .get("issueId").asText();

        Map<String, Object> noLocation = collection("餐饮", "邻家面馆");
        noLocation.remove("latitude");
        noLocation.remove("longitude");
        postJson("/api/issues/" + issueId + "/revisions", collector,
                body("revisionId", revision.id(), "collection", noLocation), 400);

        Map<String, Object> badCoordinateSystem = collection("餐饮", "邻家面馆");
        badCoordinateSystem.put("coordinateSystem", "MARS");
        postJson("/api/issues/" + issueId + "/revisions", collector,
                body("revisionId", revision.id(), "collection", badCoordinateSystem), 400);

        postJson("/api/issues/" + issueId + "/revisions", collector,
                body("revisionId", revision.id(), "collection", collection("餐饮", "邻家面馆")), 200);
    }

    @Test
    void stubInspectionShowsMapSummaryAndVisibleNotifications() throws Exception {
        String poiId = "poi-stub-" + UUID.randomUUID();
        PoiRevision revision = revision(poiId, "member-b");
        postJson("/api/reviews", reviewer,
                body("revisionId", revision.id(), "outcome", "ISSUE", "description", "与现场不符"), 200);

        JsonNode stubbed = getJson("/api/review-stub/pois?size=100", reviewer, 200);
        assertThat(strings(stubbed, "poiId")).contains(poiId);

        // 采集者能看到发给自己的通知，核验者的记录里没有这条
        assertThat(strings(getJson("/api/review-stub/notifications", collector, 200), "type"))
                .contains("REVIEW_ISSUE_REPORTED");
        // 第三方工作人员不能查看桩的 POI 摘要
        getJson("/api/review-stub/pois", worker, 403);
    }

    // =====================================================================
    // 辅助方法
    // =====================================================================

    private MockHttpSession login(String username, String password) throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", username, "password", password))))
                .andExpect(status().isOk());
        return session;
    }

    private UUID userId(String username) {
        return jdbc.queryForObject("SELECT id FROM app_users WHERE username = ?", UUID.class, username);
    }

    private PoiRevision revision(String poiId, String collectorUsername) {
        return pois.createRevision(new RevisionDraft(poiId, RevisionPurpose.NEW, userId(collectorUsername), null,
                "餐饮", "邻家面馆", "街角小面馆", 30.274112, 120.155072, 8.0, "WGS84",
                Instant.now().minusSeconds(120), "photo-" + poiId));
    }

    private static Map<String, Object> collection(String category, String name) {
        Map<String, Object> input = new HashMap<>();
        input.put("category", category);
        input.put("name", name);
        input.put("description", "现场补拍并确认招牌");
        input.put("latitude", 30.274200);
        input.put("longitude", 120.155100);
        input.put("accuracyMeters", 7.5);
        input.put("coordinateSystem", "WGS84");
        input.put("locatedAt", Instant.now().toString());
        input.put("photoId", "photo-field-" + UUID.randomUUID());
        return input;
    }

    private static Map<String, Object> body(Object... pairs) {
        Map<String, Object> body = new HashMap<>();
        for (int index = 0; index < pairs.length; index += 2) body.put((String) pairs[index], pairs[index + 1]);
        return body;
    }

    private JsonNode getJson(String path, MockHttpSession session, int expectedStatus) throws Exception {
        MvcResult result = mvc.perform(get(path).session(session)).andReturn();
        assertThat(result.getResponse().getStatus()).as(path).isEqualTo(expectedStatus);
        String responseBody = result.getResponse().getContentAsString();
        return responseBody.isBlank() ? null : json.readTree(responseBody);
    }

    private JsonNode postJson(String path, MockHttpSession session, Map<String, Object> body, int expectedStatus) throws Exception {
        return write("post", path, session, body, expectedStatus);
    }

    private JsonNode patchJson(String path, MockHttpSession session, Map<String, Object> body, int expectedStatus) throws Exception {
        return write("patch", path, session, body, expectedStatus);
    }

    private JsonNode write(String method, String path, MockHttpSession session, Map<String, Object> body, int expectedStatus) throws Exception {
        var request = "patch".equals(method)
                ? patch(path).session(session).with(csrf())
                : post(path).session(session).with(csrf());
        MvcResult result = mvc.perform(request.contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))).andReturn();
        assertThat(result.getResponse().getStatus()).as(path + " -> " + result.getResponse().getContentAsString())
                .isEqualTo(expectedStatus);
        String responseBody = result.getResponse().getContentAsString();
        return responseBody.isBlank() ? null : json.readTree(responseBody);
    }

    private List<String> pendingRevisionIds(MockHttpSession session) throws Exception {
        List<String> ids = new ArrayList<>();
        getJson("/api/reviews/pending?size=100", session, 200).get("items")
                .forEach(item -> ids.add(item.at("/revision/id").asText()));
        return ids;
    }

    private List<String> taskIds(MockHttpSession session) throws Exception {
        return taskIds(session, true);
    }

    private List<String> taskIds(MockHttpSession session, boolean mine) throws Exception {
        List<String> ids = new ArrayList<>();
        getJson("/api/field-tasks?mine=" + mine + "&size=100", session, 200).get("items")
                .forEach(item -> ids.add(item.get("id").asText()));
        return ids;
    }

    private List<String> actions(JsonNode detail) {
        List<String> actions = new ArrayList<>();
        detail.get("allowedActions").forEach(action -> actions.add(action.asText()));
        return actions;
    }

    /** 取出数组中每个元素的指定文本字段，避免依赖 JSON 库的递归查找便捷方法。 */
    private static List<String> strings(JsonNode array, String field) {
        List<String> values = new ArrayList<>();
        if (array == null || !array.isArray()) return values;
        array.forEach(item -> {
            JsonNode value = item.get(field);
            if (value != null && !value.isNull()) values.add(value.asText());
        });
        return values;
    }

    private RecordingNotificationStub.SentNotification notification(String type, String referenceId) {
        return notifications.sent().stream()
                .filter(sent -> sent.type().equals(type) && sent.reference().id().equals(referenceId))
                .findFirst().orElseThrow(() -> new AssertionError("未找到通知 " + type + " reference=" + referenceId));
    }

    private long countNotifications(String type, String referenceId) {
        return notifications.sent().stream()
                .filter(sent -> sent.type().equals(type) && sent.reference().id().equals(referenceId))
                .count();
    }
}
