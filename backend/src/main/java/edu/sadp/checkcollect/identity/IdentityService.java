package edu.sadp.checkcollect.identity;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class IdentityService {
    private final IdentityRepository users;
    private final PasswordEncoder passwords;

    public IdentityService(IdentityRepository users, PasswordEncoder passwords) {
        this.users = users;
        this.passwords = passwords;
    }

    public UserAccount currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication instanceof AnonymousAuthenticationToken || !(authentication.getPrincipal() instanceof UUID id)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        UserAccount account = users.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        if (!account.enabled()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号已停用");
        return account;
    }

    public UserAccount requireRole(Role role) {
        UserAccount account = currentUser();
        if (!account.roles().contains(role)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "权限不足");
        if (account.mustChangePassword()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "请先修改初始密码");
        return account;
    }

    public List<UserSummary> listUsers(Role role) {
        return users.listByRole(role).stream().map(UserAccount::summary).toList();
    }

    public UserAccount verifyPassword(String username, String password) {
        if (username == null || password == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入账号和密码");
        UserAccount account = users.findByUsername(username).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号或密码错误"));
        if (!account.enabled() || !passwords.matches(password, account.passwordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号或密码错误");
        }
        return account;
    }

    @Transactional
    public UserSummary createUser(String username, String displayName, String initialPassword, Set<Role> roles) {
        requireRole(Role.ADMIN);
        validateAccount(username, displayName, initialPassword, roles);
        UUID id = UUID.randomUUID();
        users.create(id, username.trim(), displayName.trim(), passwords.encode(initialPassword), roles);
        return users.find(id).orElseThrow().summary();
    }

    @Transactional
    public UserSummary updateUser(UUID id, String displayName, boolean enabled, Set<Role> roles) {
        requireRole(Role.ADMIN);
        if (displayName == null || displayName.isBlank() || roles == null || roles.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "姓名和角色不能为空");
        }
        users.lockAdminUpdates();
        UserAccount before = users.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        users.update(id, displayName.trim(), enabled, roles);
        if (before.enabled() && before.roles().contains(Role.ADMIN) && (!enabled || !roles.contains(Role.ADMIN)) && users.countEnabledAdmins() == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "必须保留一名启用的管理员");
        }
        return users.find(id).orElseThrow().summary();
    }

    @Transactional
    public void changePassword(String oldPassword, String newPassword) {
        UserAccount account = currentUser();
        if (!passwords.matches(oldPassword, account.passwordHash())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "原密码错误");
        if (newPassword == null || newPassword.length() < 10 || newPassword.length() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "新密码须为10至128个字符");
        }
        users.changePassword(account.id(), passwords.encode(newPassword));
    }

    @Transactional
    public void bindWechat(UUID id, String appId, String openId) {
        UserAccount account = users.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!account.enabled()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        UserAccount existing = users.findByWechat(appId, openId).orElse(null);
        if (existing != null && existing.id().equals(id)) return;
        if (existing != null || account.wechatBound()) throw new ResponseStatusException(HttpStatus.CONFLICT, "微信已绑定其他账号或此账号已有绑定");
        try { users.bindWechat(id, appId, openId); }
        catch (DataIntegrityViolationException error) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "微信绑定已被其他请求完成", error);
        }
    }

    public UserAccount userForWechat(String appId, String openId) {
        UserAccount account = users.findByWechat(appId, openId).orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "请先用团队账号登录并绑定微信"));
        if (!account.enabled()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号已停用");
        return account;
    }

    public UserAccount activeUser(UUID id) {
        UserAccount account = users.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号不存在"));
        if (!account.enabled()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号已停用");
        return account;
    }

    /** 该微信是否已绑定账号；未绑定时小程序可以引导用户自助注册。 */
    public java.util.Optional<UserSummary> findByWechat(String appId, String openId) {
        return users.findByWechat(appId, openId).map(UserAccount::summary);
    }

    /**
     * 小程序扫码自助注册：openid 已由后端向微信换取，这里只创建**采集者**账号并绑定，
     * 不允许自助选择核验者、第三方或管理员，那些角色仍由管理员分配。
     */
    @Transactional
    public UserAccount registerWithWechat(String appId, String openId, String username, String password, String displayName) {
        if (appId == null || appId.isBlank() || openId == null || openId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少小程序身份，请重新扫码");
        }
        String name = displayName == null || displayName.isBlank() ? username : displayName.trim();
        validateAccount(username, name, password, Set.of(Role.COLLECTOR));
        if (users.findByUsername(username.trim()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "账号名已被使用，请换一个");
        }
        if (users.findByWechat(appId, openId).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该微信已绑定账号，请直接扫码登录");
        }
        UUID id = UUID.randomUUID();
        String hash = passwords.encode(password);
        try {
            users.create(id, username.trim(), name, hash, Set.of(Role.COLLECTOR));
            // 密码由本人设置，不再强制首次改密
            users.changePassword(id, hash);
            users.bindWechat(id, appId, openId);
        } catch (DataIntegrityViolationException error) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "账号或微信绑定已被占用", error);
        }
        return users.find(id).orElseThrow();
    }

    public List<UserSummary> allUsers() {
        requireRole(Role.ADMIN);
        return users.list().stream().map(UserAccount::summary).toList();
    }

    private void validateAccount(String username, String name, String password, Set<Role> roles) {
        if (username == null || !username.matches("[A-Za-z0-9._-]{3,64}") || name == null || name.isBlank() || name.length() > 100
                || password == null || password.length() < 10 || password.length() > 128 || roles == null || roles.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "账号、姓名、初始密码或角色无效");
        }
    }
}
