package edu.sadp.checkcollect.identity;

import jakarta.servlet.http.*;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth/wechat/mini/pairings")
public class MiniWechatPairingController {
    private static final String OWNER = MiniWechatPairingController.class.getName() + ".owner";
    private final MiniWechatPairings pairings;
    private final MiniWechatClient client;
    private final IdentityService identity;
    private final SessionLogin login;

    public MiniWechatPairingController(MiniWechatPairings pairings, MiniWechatClient client, IdentityService identity, SessionLogin login) {
        this.pairings = pairings; this.client = client; this.identity = identity; this.login = login;
    }

    @PostMapping
    public MiniWechatPairings.Created create(HttpServletRequest request) {
        if (!client.configured()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "小程序微信登录尚未配置");
        HttpSession session = request.getSession(true);
        synchronized (session) {
            if (session.getAttribute(OWNER) == null) session.setAttribute(OWNER, UUID.randomUUID().toString());
            return pairings.create((String) session.getAttribute(OWNER));
        }
    }

    @GetMapping("/{id}")
    public Map<String, String> status(@PathVariable String id, HttpServletRequest request) {
        return Map.of("status", pairings.status(id, owner(request)));
    }

    public record Confirmation(String code) {}
    @PostMapping("/confirm")
    public void confirm(@RequestBody Confirmation body) {
        pairings.confirm(body.code(), identity.currentUser().id());
    }

    @PostMapping("/{id}/consume")
    public UserSummary consume(@PathVariable String id, HttpServletRequest request, HttpServletResponse response) {
        UUID userId = pairings.consume(id, owner(request));
        UserAccount account = identity.activeUser(userId);
        login.establish(account.id(), request, response);
        response.setHeader("Cache-Control", "no-store");
        return account.summary();
    }

    @DeleteMapping("/{id}")
    public void cancel(@PathVariable String id, HttpServletRequest request) { pairings.cancel(id, owner(request)); }

    private String owner(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session == null ? null : (String) session.getAttribute(OWNER);
    }
}
