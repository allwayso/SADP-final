package edu.sadp.checkcollect.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import edu.sadp.checkcollect.messaging.*;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.*;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(
    properties = {
      "app.review.poi-stub=false",
      "app.review.notification-stub=false",
      "app.review.demo-data=false",
      "app.messaging.transport=in-process",
      "app.messaging.stub-enabled=false",
      "APP_SEED_DEMO=true",
      "DEMO_PASSWORD=integration-demo-pass",
      "INITIAL_ADMIN_USERNAME=test-admin",
      "INITIAL_ADMIN_PASSWORD=integration-admin-pass"
    })
class IntegratedWorkflowTest {
  static final EmbeddedPostgres DB;

  static {
    try {
      DB = EmbeddedPostgres.builder().start();
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> DB.getJdbcUrl("postgres", "postgres"));
    registry.add("spring.datasource.username", () -> "postgres");
    registry.add("spring.datasource.password", () -> "");
    registry.add(
        "app.photo.dir",
        () -> System.getProperty("java.io.tmpdir") + "/checkcollect-integration-" + DB.getPort());
  }

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired BrokerSimulation broker;
  @Autowired NotificationPublisher publisher;
  @Autowired NotificationDeliveryService delivery;
  JsonMapper json = JsonMapper.builder().build();
  MockHttpSession collector, reviewer, worker, other;

  @BeforeAll
  void accounts() throws Exception {
    jdbc.update("UPDATE app_users SET must_change_password=false");
    collector = login("member-b");
    reviewer = login("member-c");
    worker = login("member-d");
    other = login("member-a");
  }

  @AfterAll
  void close() throws Exception {
    DB.close();
  }

  MockHttpSession login(String username) throws Exception {
    MockHttpSession session = new MockHttpSession();
    send(
        post("/api/auth/login")
            .session(session)
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                json.writeValueAsString(
                    Map.of("username", username, "password", "integration-demo-pass"))),
        200);
    return session;
  }

  JsonNode send(org.springframework.test.web.servlet.RequestBuilder request, int status)
      throws Exception {
    var response = mvc.perform(request).andReturn().getResponse();
    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
    return response.getContentAsString().isBlank()
        ? null
        : json.readTree(response.getContentAsString());
  }

  JsonNode getJson(String path, MockHttpSession session, int status) throws Exception {
    return send(get(path).session(session), status);
  }

  JsonNode postJson(String path, MockHttpSession session, Object body, int status)
      throws Exception {
    return send(
        post(path)
            .session(session)
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(body)),
        status);
  }

  MockMultipartFile photo() {
    return new MockMultipartFile(
        "photos",
        "shop.png",
        "image/png",
        Base64.getDecoder()
            .decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j2ioAAAAASUVORK5CYII="));
  }

  JsonNode collect(String poi, String expected, MockHttpSession user, int status) throws Exception {
    var request =
        multipart(poi == null ? "/api/pois" : "/api/pois/" + poi + "/revisions")
            .file(photo())
            .session(user)
            .with(csrf())
            .param("category", "SHOP")
            .param("name", "测试商铺")
            .param("description", "现场采集")
            .param("lat", "30.27")
            .param("lon", "120.15")
            .param("crs", "WGS84")
            .param("locatedAt", Instant.now().toString())
            .param("accuracyMeters", "5");
    if (expected != null) request.param("expectedRevisionId", expected);
    return send(request, status);
  }

  Map<String, Object> collection() {
    return Map.of(
        "category",
        "SHOP",
        "name",
        "现场商铺",
        "description",
        "现场重新采集",
        "latitude",
        30.27,
        "longitude",
        120.15,
        "accuracyMeters",
        4,
        "coordinateSystem",
        "WGS84",
        "locatedAt",
        Instant.now().toString(),
        "photoId",
        "uploaded");
  }

  JsonNode files(String path, MockHttpSession user, Map<String, Object> body, int status)
      throws Exception {
    var data =
        new MockMultipartFile(
            "request", "request.json", "application/json", json.writeValueAsBytes(body));
    return send(multipart(path).file(data).file(photo()).session(user).with(csrf()), status);
  }

  String issue(JsonNode poi) throws Exception {
    return postJson(
            "/api/reviews",
            reviewer,
            Map.of(
                "revisionId",
                poi.get("revisionId").asText(),
                "outcome",
                "ISSUE",
                "description",
                "招牌需要复核"),
            200)
        .get("issueId")
        .asText();
  }

  @Test
  void realCollectionAppearsInReviewWithDeliveredNotificationAndPhoto() throws Exception {
    JsonNode poi = collect(null, null, collector, 200);
    String revision = poi.get("revisionId").asText();
    assertThat(getJson("/api/reviews/pending", reviewer, 200).toString()).contains(revision);
    JsonNode detail = getJson("/api/pois/" + poi.get("poiId").asText(), collector, 200);
    String photo = detail.at("/revisions/0/photos/0/id").asText();
    mvc.perform(
            get("/api/pois/" + poi.get("poiId").asText() + "/photos/" + photo).session(reviewer))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE reference_id=? AND status='DELIVERED'",
                Long.class,
                poi.get("poiId").asText()))
        .isEqualTo(1);
  }

  @Test
  void foreignAndStaleUpdatesAreRejectedAndNewRevisionResetsSummary() throws Exception {
    JsonNode poi = collect(null, null, collector, 200);
    String id = poi.get("poiId").asText(), rev = poi.get("revisionId").asText();
    collect(id, rev, other, 403);
    postJson("/api/reviews", reviewer, Map.of("revisionId", rev, "outcome", "PASSED"), 200);
    collect(id, rev, collector, 200);
    collect(id, rev, collector, 409);
    assertThat(getJson("/api/pois/" + id, collector, 200).at("/poi/review/progress").asText())
        .isEqualTo("PENDING_REVIEW");
  }

  @Test
  void issueCorrectionUpdatesRealPhotosSummaryAndBlocksBypass() throws Exception {
    JsonNode poi = collect(null, null, collector, 200);
    String id = poi.get("poiId").asText(), rev = poi.get("revisionId").asText(), issue = issue(poi);
    collect(id, rev, collector, 409);
    JsonNode corrected =
        files(
            "/api/issues/" + issue + "/revisions",
            collector,
            Map.of("revisionId", rev, "collection", collection()),
            200);
    String newRev = corrected.at("/issue/currentRevisionId").asText();
    assertThat(newRev).isNotEqualTo(rev);
    assertThat(getJson("/api/pois/" + id, collector, 200).at("/poi/review/progress").asText())
        .isEqualTo("PENDING_RECHECK");
    postJson(
        "/api/issues/" + issue + "/confirmations", reviewer, Map.of("revisionId", newRev), 200);
    assertThat(getJson("/api/pois/" + id, collector, 200).at("/poi/review/progress").asText())
        .isEqualTo("PASSED");
  }

  @Test
  void actualIssueCreatesPrivateConversationAndRejectsUnrelatedUser() throws Exception {
    String issue = issue(collect(null, null, collector, 200));
    String conversation =
        getJson("/api/issues/" + issue, collector, 200).get("conversationId").asText();
    postJson(
        "/api/conversations/" + conversation + "/messages",
        collector,
        Map.of("body", "请检查照片"),
        200);
    assertThat(
            getJson("/api/conversations/" + conversation + "/messages", reviewer, 200).toString())
        .contains("请检查照片");
    getJson("/api/conversations/" + conversation + "/messages", worker, 403);
  }

  @Test
  void bothVerdictsRequireAndKeepFieldEvidenceAndChooseCorrectEffectiveVersion() throws Exception {
    for (String verdict : List.of("ORIGINAL_CORRECT", "REPLACED")) {
      JsonNode poi = collect(null, null, collector, 200);
      String id = poi.get("poiId").asText(),
          rev = poi.get("revisionId").asText(),
          issue = issue(poi);
      postJson(
          "/api/issues/" + issue + "/rejections",
          collector,
          Map.of("revisionId", rev, "note", "现场情况有异议"),
          200);
      postJson(
          "/api/disputes",
          collector,
          Map.of("issueId", issue, "revisionId", rev, "reason", "沟通后仍需现场核实"),
          200);
      String task = getJson("/api/issues/" + issue, collector, 200).at("/task/id").asText();
      postJson(
          "/api/field-tasks/" + task + "/result",
          worker,
          Map.of("verdict", verdict, "reason", "无证据"),
          400);
      files(
          "/api/field-tasks/" + task + "/result",
          collector,
          Map.of("verdict", verdict, "reason", "越权", "collection", collection()),
          403);
      JsonNode decided =
          files(
              "/api/field-tasks/" + task + "/result",
              worker,
              Map.of("verdict", verdict, "reason", "现场已核实", "collection", collection()),
              200);
      assertThat(decided.get("fieldRevisionId").asText()).isNotBlank();
      String expected = verdict.equals("REPLACED") ? decided.get("fieldRevisionId").asText() : rev;
      JsonNode detail = getJson("/api/pois/" + id, collector, 200);
      assertThat(detail.at("/poi/currentRevisionId").asText()).isEqualTo(expected);
      assertThat(detail.at("/poi/review/progress").asText()).isEqualTo("FINAL");
      assertThat(detail.get("revisions").size()).isEqualTo(2);
      files(
          "/api/field-tasks/" + task + "/result",
          worker,
          Map.of("verdict", verdict, "reason", "重复裁定", "collection", collection()),
          409);
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM notifications WHERE reference_id=? AND kind='FINAL_RESULT'",
                  Long.class,
                  issue))
          .isEqualTo(2);
    }
  }

  @Test
  void announcementRequiresAdminAndDebugEndpointsAreDisabled() throws Exception {
    postJson("/api/announcements", collector, Map.of("title", "公告", "body", "暂停工作"), 403);
    postJson("/api/announcements", other, Map.of("title", "公告", "body", "暂停工作"), 200);
    postJson("/api/messaging/stub/fault", other, Map.of("down", true), 404);
    assertThat(getJson("/api/notifications", collector, 200).toString()).contains("暂停工作");
  }

  @Test
  void unavailableTransportDoesNotLoseBusinessAndRedeliveryIsIdempotent() throws Exception {
    JsonNode poi;
    broker.setAvailable(false);
    try {
      poi = collect(null, null, collector, 200);
    } finally {
      broker.setAvailable(true);
    }
    UUID notification =
        jdbc.queryForObject(
            "SELECT id FROM notifications WHERE reference_id=?",
            UUID.class,
            poi.get("poiId").asText());
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM notifications WHERE id=?", String.class, notification))
        .isEqualTo("FAILED");
    publisher.retryPending(true);
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM notifications WHERE id=?", String.class, notification))
        .isEqualTo("DELIVERED");
    assertThat(delivery.deliver(notification).newlyDeliveredRecipients()).isZero();
  }
}
