package edu.sadp.checkcollect.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 小程序内的微信登录与绑定。
 *
 * <p>与扫码确认不同，这里由小程序直接完成身份交换：`wx.login` 拿到一次性 code，
 * 后端用 code 向微信换取 openid，再按下面的顺序处理：
 * <ul>
 *   <li>该微信已绑定账号 → 建立登录会话；</li>
 *   <li>未绑定且请求带账号密码 → 校验密码后把微信绑定到该账号并登录；</li>
 *   <li>未绑定且请求要求注册 → 创建采集者账号并绑定，然后登录；</li>
 *   <li>其余情况返回 {@code need-binding}，由小程序让用户选择绑定或注册。</li>
 * </ul>
 *
 * <p>角色只能通过注册成为采集者；核验者、第三方工作人员和管理员仍由管理员分配。
 */
@RestController
@RequestMapping("/api/auth/wechat/mini")
public class MiniWechatSessionController {
    private final MiniWechatClient client;
    private final IdentityService identity;
    private final SessionLogin login;

    public MiniWechatSessionController(MiniWechatClient client, IdentityService identity, SessionLogin login) {
        this.client = client;
        this.identity = identity;
        this.login = login;
    }

    @GetMapping("/status")
    public Map<String, Boolean> status() {
        return Map.of("configured", client.configured());
    }

    /**
     * 用微信身份登录，必要时在同一请求里完成绑定或注册。
     * {@code action} 为空表示只尝试登录；{@code bind} 需要账号密码；{@code register} 需要新账号信息。
     */
    @PostMapping("/session")
    public Map<String, Object> session(@RequestBody(required = false) SessionRequest body,
                                       HttpServletRequest request, HttpServletResponse response) {
        if (!client.configured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "小程序微信登录尚未配置");
        }
        if (body == null || body.code() == null || body.code().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少微信登录凭证");
        }
        String action = body.action() == null || body.action().isBlank() ? "login" : body.action().trim();
        if (!Set.of("login", "bind", "register").contains(action)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的登录动作");
        }

        String openId = client.openId(body.code());
        var bound = identity.findByWechat(client.appId, openId);
        if (bound.isPresent()) {
            UserAccount account = identity.userForWechat(client.appId, openId);
            login.establish(account.id(), request, response);
            return Map.of("status", "logged-in", "user", account.summary());
        }

        if ("bind".equals(action)) {
            UserAccount account = identity.verifyPassword(body.username(), body.password());
            identity.bindWechat(account.id(), client.appId, openId);
            login.establish(account.id(), request, response);
            return Map.of("status", "bound", "user", identity.currentUser().summary());
        }

        if ("register".equals(action)) {
            UserAccount account = identity.registerWithWechat(client.appId, openId,
                    body.username(), body.password(), body.displayName());
            login.establish(account.id(), request, response);
            return Map.of("status", "registered", "user", account.summary());
        }

        return Map.of("status", "need-binding");
    }

    /**
     * @param code        wx.login 返回的临时凭证，只能用一次
     * @param action      login / bind / register
     * @param username    bind 时为已有账号名，register 时为新账号名
     * @param password    bind 时为账号密码，register 时为设定的密码
     * @param displayName register 时可选，缺省用账号名
     */
    public record SessionRequest(String code, String action, String username, String password, String displayName) {
    }

    /** 小程序退出登录：结束当前微信端的会话，不影响其他端。 */
    @PostMapping("/logout")
    public void logout(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session != null) session.invalidate();
    }
}
