package edu.sadp.checkcollect.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 微信小程序扫码登录/绑定：网站生成一次性 ticket，小程序在确认页用 wx.login 的 code 换取 openid，
 * 原浏览器再凭同一会话消费 ticket 完成登录或绑定。
 *
 * <p>ticket 保存在本实例内存中，五分钟有效且一次性使用，因此当前只支持单实例部署。
 * 二维码由微信接口生成；若平台拒绝（例如个人主体未通过验证），接口仍返回 ticket 与原因，
 * 页面可以改用开发者工具预览码完成联调，而不是把失败伪装成成功。
 */
@RestController
@RequestMapping("/api/auth/wechat/mini")
public class MiniWechatController {
    private static final int TICKET_LIMIT = 1000;
    private static final long TICKET_TTL_SECONDS = 300;

    private final SecureRandom random = new SecureRandom();
    private final MiniWechatClient client;
    private final IdentityService identity;
    private final SessionLogin login;
    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();

    private record Ticket(String sessionId, UUID userId, Instant expires, String pairing, String openId) {
        Ticket confirmed(String value) {
            return new Ticket(sessionId, userId, expires, pairing, value);
        }
    }

    public MiniWechatController(MiniWechatClient client, IdentityService identity, SessionLogin login) {
        this.client = client;
        this.identity = identity;
        this.login = login;
    }

    @GetMapping("/status")
    public Map<String, Boolean> status() {
        return Map.of("configured", client.configured());
    }

    /** 生成一次性 ticket。{@code purpose=LOGIN} 用于扫码登录，{@code BIND} 用于已登录账号绑定微信。 */
    @PostMapping("/create")
    public synchronized Map<String, String> create(@RequestBody(required = false) CreateRequest body, HttpServletRequest request) {
        if (!client.configured()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "小程序登录尚未配置");
        if (body == null || body.purpose() == null || !Set.of("LOGIN", "BIND").contains(body.purpose())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请指定登录或绑定");
        }
        UUID userId = null;
        if ("BIND".equals(body.purpose())) {
            var user = identity.currentUser();
            if (user.wechatBound()) throw new ResponseStatusException(HttpStatus.CONFLICT, "已绑定微信");
            userId = user.id();
        }

        String sessionId = request.getSession(true).getId();
        tickets.entrySet().removeIf(entry -> entry.getValue().expires().isBefore(Instant.now())
                || entry.getValue().sessionId().equals(sessionId));
        if (tickets.size() >= TICKET_LIMIT) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "请求过多");

        String ticket = UUID.randomUUID().toString().replace("-", "");
        String pairing = pairingCode();
        String image = "";
        String warning = null;
        try {
            byte[] qr = client.qr(ticket);
            image = "data:image/png;base64," + Base64.getEncoder().encodeToString(qr);
        } catch (ResponseStatusException error) {
            // 平台拒绝生码时保留原因，页面据此改用开发者工具预览码或配对码联调
            warning = error.getReason();
        }
        tickets.put(ticket, new Ticket(sessionId, userId, Instant.now().plusSeconds(TICKET_TTL_SECONDS), pairing, null));

        Map<String, String> result = new HashMap<>();
        result.put("ticket", ticket);
        result.put("pairing", pairing);
        result.put("image", image);
        if (warning != null) result.put("warning", warning);
        return result;
    }

    /**
     * 小程序确认页调用：用 wx.login 的 code 换取 openid，并绑定到已存在的请求。
     * 生码被平台拒绝时，小程序可以用网页显示的十二位配对码代替 ticket，流程不变。
     */
    @PostMapping("/confirm")
    public void confirm(@RequestBody(required = false) ConfirmRequest body) {
        if (body == null || body.code() == null || body.code().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少微信登录凭证");
        }
        String ticketId = resolveTicketId(body.ticket(), body.pairing());
        Ticket initial = valid(ticketId);
        if (initial.openId() != null) throw new ResponseStatusException(HttpStatus.CONFLICT, "该请求已确认");
        String openId = client.openId(body.code());
        synchronized (this) {
            Ticket current = valid(ticketId);
            if (current != initial || current.openId() != null) throw new ResponseStatusException(HttpStatus.CONFLICT, "该请求已确认");
            tickets.put(ticketId, current.confirmed(openId));
        }
    }

    /** 原浏览器轮询：未确认返回 pending，确认后建立会话或完成绑定并删除 ticket。 */
    @PostMapping("/consume")
    public synchronized Map<String, Object> consume(@RequestBody(required = false) ConsumeRequest body,
                                                    HttpServletRequest request, HttpServletResponse response) {
        Ticket ticket = valid(body == null ? null : body.ticket());
        if (request.getSession(false) == null || !ticket.sessionId().equals(request.getSession(false).getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "该请求不属于当前会话");
        }
        if (ticket.openId() == null) return Map.of("status", "pending");
        tickets.remove(body.ticket());
        UserAccount account;
        if (ticket.userId() != null) {
            account = identity.currentUser();
            if (!account.id().equals(ticket.userId())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "该请求不属于当前账号");
            }
            identity.bindWechat(account.id(), client.appId, ticket.openId());
            account = identity.currentUser();
        } else {
            account = identity.userForWechat(client.appId, ticket.openId());
            login.establish(account.id(), request, response);
        }
        return Map.of("status", "complete", "user", account.summary());
    }

    private Ticket valid(String id) {
        Ticket ticket = id == null ? null : tickets.get(id);
        if (ticket == null || !ticket.expires().isAfter(Instant.now())) {
            if (id != null) tickets.remove(id);
            throw new ResponseStatusException(HttpStatus.GONE, "二维码或配对码已过期，请重新获取");
        }
        return ticket;
    }

    /** 允许小程序用 ticket 或十二位配对码定位同一条请求。 */
    private String resolveTicketId(String ticket, String pairing) {
        if (ticket != null && !ticket.isBlank()) return ticket;
        if (pairing == null || !pairing.matches("[A-Fa-f0-9]{12}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写网页上的十二位配对码");
        }
        return tickets.entrySet().stream()
                .filter(entry -> pairing.equalsIgnoreCase(entry.getValue().pairing()))
                .filter(entry -> entry.getValue().expires().isAfter(Instant.now()))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.GONE, "配对码无效或已过期，请回网站重新获取"));
    }

    /** 生成当前未被占用的十二位配对码，避免同时等待的两个浏览器互串。 */
    private String pairingCode() {
        Set<String> used = new java.util.HashSet<>();
        for (Ticket item : tickets.values()) {
            if (item.expires().isAfter(Instant.now())) used.add(item.pairing());
        }
        for (int attempt = 0; attempt < 50; attempt++) {
            byte[] bytes = new byte[6];
            random.nextBytes(bytes);
            String code = java.util.HexFormat.of().withUpperCase().formatHex(bytes);
            if (!used.contains(code)) return code;
        }
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "当前等待的登录请求过多");
    }

    public record CreateRequest(String purpose) {
    }

    /** {@code code} 为 wx.login 返回的临时凭证；{@code ticket} 与 {@code pairing} 二选一。 */
    public record ConfirmRequest(String ticket, String pairing, String code) {
    }

    public record ConsumeRequest(String ticket) {
    }
}
