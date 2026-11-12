package edu.sadp.checkcollect.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.*;
import tools.jackson.databind.json.JsonMapper;

/** Real security, database and sessions; only the external WeChat code exchange is mocked. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "app.review.poi-stub=false", "app.review.notification-stub=false",
    "app.review.demo-data=false", "app.messaging.transport=in-process",
    "app.messaging.stub-enabled=false", "APP_SEED_DEMO=true",
    "DEMO_PASSWORD=acceptance-demo-pass", "INITIAL_ADMIN_USERNAME=acceptance-admin",
    "INITIAL_ADMIN_PASSWORD=acceptance-admin-pass", "WECHAT_MINI_APP_ID=acceptance-app", "WECHAT_MINI_APP_SECRET=acceptance-fake-secret"
})
class MiniWechatAcceptanceTest {
    static final EmbeddedPostgres DB;
    static { try { DB = EmbeddedPostgres.builder().start(); } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); } }
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> DB.getJdbcUrl("postgres", "postgres"));
        r.add("spring.datasource.username", () -> "postgres");
        r.add("spring.datasource.password", () -> "");
    }
    @AfterAll static void close() throws Exception { DB.close(); }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean MiniWechatClient client;
    final JsonMapper json = JsonMapper.builder().build();
    MvcResult session(MockHttpSession s, Map<String,String> body) throws Exception {
        return mvc.perform(post("/api/auth/wechat/mini/session").session(s)
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andReturn();
    }
    @Test void registrationPersistsOnlyCollectorAndSessionsStaySeparate() throws Exception {
        doReturn("acceptance-register").when(client).openId(anyString());
        var s = new MockHttpSession();
        var result = session(s, Map.of("code","fresh", "action","register", "username","acceptance-new", "password","acceptance-new-pass"));
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        var body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("status").asText()).isEqualTo("registered");
        assertThat(body.path("user").path("roles").toString()).isEqualTo("[\"COLLECTOR\"]");
        assertThat(body.path("user").path("mustChangePassword").asBoolean()).isFalse();
        assertThat(mvc.perform(get("/api/me").session(s)).andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(mvc.perform(get("/api/me")).andReturn().getResponse().getStatus()).isEqualTo(401);
        var relogin = session(new MockHttpSession(), Map.of("code","fresh-again"));
        assertThat(json.readTree(relogin.getResponse().getContentAsString()).path("user").path("id").asText())
            .isEqualTo(body.path("user").path("id").asText());
        assertThat(mvc.perform(post("/api/auth/wechat/mini/logout").session(s).with(csrf())).andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(s.isInvalid()).isTrue();
    }
    @Test void disabledWechatAccountCannotLogin() throws Exception {
        doReturn("acceptance-disabled").when(client).openId(anyString());
        var id = jdbc.queryForObject("SELECT id FROM app_users WHERE username='member-d'", java.util.UUID.class);
        jdbc.update("INSERT INTO wechat_bindings(user_id,app_id,open_id) VALUES (?,?,?)", id, "acceptance-app", "acceptance-disabled");
        jdbc.update("UPDATE app_users SET enabled=false WHERE id=?", id);
        assertThat(session(new MockHttpSession(), Map.of("code","disabled-code")).getResponse().getStatus()).isEqualTo(401);
    }
    @Test void initialPasswordAccountCanReallyLogoutAfterBinding() throws Exception {
        doReturn("acceptance-initial-password").when(client).openId(anyString());
        var s = new MockHttpSession();
        var bound = session(s, Map.of("code","fresh-bind", "action","bind", "username","member-b", "password","acceptance-demo-pass"));
        assertThat(bound.getResponse().getStatus()).isEqualTo(200);
        var body = json.readTree(bound.getResponse().getContentAsString());
        assertThat(body.path("user").path("mustChangePassword").asBoolean()).isTrue();
        assertThat(mvc.perform(post("/api/auth/wechat/mini/logout").session(s)).andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(s.isInvalid()).isFalse();
        assertThat(mvc.perform(post("/api/auth/wechat/mini/logout").session(s).with(csrf())).andReturn().getResponse().getStatus())
            .as("new mini logout must be allowed for a first-login account").isEqualTo(200);
        assertThat(s.isInvalid()).isTrue();
    }

    tools.jackson.databind.JsonNode createPairing(MockHttpSession browser) throws Exception {
        var result = mvc.perform(post("/api/auth/wechat/mini/pairings").session(browser).with(csrf())).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    @Test void browserLoginNeedsBothMiniApprovalAndOriginalBrowserAndCsrf() throws Exception {
        doReturn("acceptance-pairing").when(client).openId(anyString());
        var mini = new MockHttpSession();
        var signedIn = session(mini, Map.of("code","pair-code", "action","register", "username","pair-collector", "password","pair-collector-pass"));
        String userId = json.readTree(signedIn.getResponse().getContentAsString()).path("user").path("id").asText();
        var browser = new MockHttpSession();
        assertThat(mvc.perform(post("/api/auth/wechat/mini/pairings").session(browser)).andReturn().getResponse().getStatus()).isEqualTo(403);
        var pairing = createPairing(browser);
        String path = "/api/auth/wechat/mini/pairings/" + pairing.path("id").asText();
        String approval = json.writeValueAsString(Map.of("code",pairing.path("code").asText()));
        assertThat(mvc.perform(post("/api/auth/wechat/mini/pairings/confirm").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(approval)).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(post("/api/auth/wechat/mini/pairings/confirm").session(mini).contentType(MediaType.APPLICATION_JSON).content(approval)).andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(mvc.perform(post(path + "/consume").session(browser).with(csrf())).andReturn().getResponse().getStatus()).isEqualTo(409);
        assertThat(mvc.perform(get(path).session(new MockHttpSession())).andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(mvc.perform(post("/api/auth/wechat/mini/pairings/confirm").session(mini).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(approval)).andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(mvc.perform(post(path + "/consume").session(new MockHttpSession()).with(csrf())).andReturn().getResponse().getStatus()).isEqualTo(403);
        String oldSessionId = browser.getId();
        var consumed = mvc.perform(post(path + "/consume").session(browser).with(csrf())).andReturn();
        assertThat(consumed.getResponse().getStatus()).isEqualTo(200);
        assertThat(browser.getId()).isNotEqualTo(oldSessionId);
        assertThat(json.readTree(consumed.getResponse().getContentAsString()).path("id").asText()).isEqualTo(userId);
        var profile = mvc.perform(get("/api/me").session(browser)).andReturn();
        assertThat(profile.getResponse().getStatus()).isEqualTo(200);
        assertThat(json.readTree(profile.getResponse().getContentAsString()).path("id").asText()).isEqualTo(userId);
        assertThat(mvc.perform(post(path + "/consume").session(browser).with(csrf())).andReturn().getResponse().getStatus()).isEqualTo(409);
        assertThat(json.readTree(mvc.perform(get(path).session(browser)).andReturn().getResponse().getContentAsString()).path("status").asText()).isEqualTo("consumed");
    }

    @Test void initialPasswordAccountCanApproveWebsiteLoginAndDisabledAccountCannotConsume() throws Exception {
        doReturn("acceptance-password-pairing").when(client).openId(anyString());
        var mini = new MockHttpSession();
        var bound = session(mini, Map.of("code","bind-c", "action","bind", "username","member-c", "password","acceptance-demo-pass"));
        assertThat(bound.getResponse().getStatus()).isEqualTo(200);
        var browser = new MockHttpSession();
        var pairing = createPairing(browser);
        assertThat(mvc.perform(post("/api/auth/wechat/mini/pairings/confirm").session(mini).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("code",pairing.path("code").asText())))).andReturn().getResponse().getStatus()).isEqualTo(200);
        jdbc.update("UPDATE app_users SET enabled=false WHERE username='member-c'");
        assertThat(mvc.perform(post("/api/auth/wechat/mini/pairings/" + pairing.path("id").asText() + "/consume").session(browser).with(csrf())).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(get("/api/me").session(browser)).andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    @Test void bindingConflictAndRegistrationCannotClaimPrivilegedRoles() throws Exception {
        doReturn("acceptance-role-check").when(client).openId(anyString());
        var result = mvc.perform(post("/api/auth/wechat/mini/session").session(new MockHttpSession()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"new\",\"action\":\"register\",\"username\":\"role-check\",\"password\":\"role-check-pass\",\"roles\":[\"ADMIN\"]}")).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(json.readTree(result.getResponse().getContentAsString()).path("user").path("roles").toString()).isEqualTo("[\"COLLECTOR\"]");
        doReturn("acceptance-other-wechat").when(client).openId(anyString());
        assertThat(session(new MockHttpSession(), Map.of("code","other", "action","bind", "username","role-check", "password","role-check-pass")).getResponse().getStatus()).isEqualTo(409);
        assertThat(session(new MockHttpSession(), Map.of("code","other-new", "action","register", "username","role-check", "password","role-check-pass")).getResponse().getStatus()).isEqualTo(409);
    }
}
