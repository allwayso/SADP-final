package edu.sadp.checkcollect.identity;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Single-instance, bounded, short-lived browser login approvals. All transitions are atomic. */
@Component
public class MiniWechatPairings {
    private static final int CAPACITY = 1000;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Pairing> requests = new HashMap<>();
    private final Map<String, Deque<Instant>> creates = new HashMap<>();
    private final Map<UUID, Deque<Instant>> confirmations = new HashMap<>();

    public MiniWechatPairings() { this(Clock.systemUTC()); }
    MiniWechatPairings(Clock clock) { this.clock = clock; }

    private static class Pairing {
        final String id, code, owner;
        final Instant expiresAt;
        UUID userId;
        String state = "pending";
        Pairing(String id, String code, String owner, Instant expiresAt) {
            this.id = id; this.code = code; this.owner = owner; this.expiresAt = expiresAt;
        }
    }
    public record Created(String id, String code, Instant expiresAt) {}

    public synchronized Created create(String owner) {
        Instant now = clock.instant();
        requests.values().removeIf(p -> !p.expiresAt.isAfter(now));
        rate(creates, owner, 3, now);
        requests.values().stream().filter(p -> p.owner.equals(owner) && !p.state.equals("consumed"))
            .forEach(p -> p.state = "cancelled");
        if (requests.size() >= CAPACITY) throw error(HttpStatus.TOO_MANY_REQUESTS, "登录请求过多，请稍后再试");
        String code;
        do { code = String.format(Locale.ROOT, "%08d", random.nextInt(100_000_000)); }
        while (codeExists(code));
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String id = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Pairing pairing = new Pairing(id, code, owner, now.plusSeconds(300));
        requests.put(id, pairing);
        return new Created(id, code, pairing.expiresAt);
    }

    private boolean codeExists(String code) { return requests.values().stream().anyMatch(p -> p.code.equals(code)); }

    public synchronized String status(String id, String owner) {
        Pairing p = owned(id, owner);
        return p.expiresAt.isAfter(clock.instant()) ? p.state : "expired";
    }

    public synchronized void confirm(String code, UUID userId) {
        Instant now = clock.instant();
        rate(confirmations, userId, 5, now);
        if (code == null || !code.matches("[0-9]{8}")) throw error(HttpStatus.BAD_REQUEST, "请输入网页上的 8 位配对码");
        Pairing p = requests.values().stream().filter(item -> item.code.equals(code)).findFirst()
            .orElseThrow(() -> error(HttpStatus.GONE, "配对码无效或已过期，请在网页重新发起"));
        if (!p.expiresAt.isAfter(now)) throw error(HttpStatus.GONE, "配对码已过期，请在网页重新发起");
        if (!p.state.equals("pending")) throw error(HttpStatus.CONFLICT, "该登录请求已确认、取消或使用");
        p.userId = userId;
        p.state = "approved";
    }

    public synchronized UUID consume(String id, String owner) {
        Pairing p = owned(id, owner);
        if (!p.expiresAt.isAfter(clock.instant())) throw error(HttpStatus.GONE, "配对码已过期，请重新发起");
        if (!p.state.equals("approved")) throw error(HttpStatus.CONFLICT, "登录请求尚未确认或已使用");
        p.state = "consumed";
        return p.userId;
    }

    public synchronized void cancel(String id, String owner) {
        Pairing p = owned(id, owner);
        if (!p.state.equals("consumed")) p.state = "cancelled";
    }

    private Pairing owned(String id, String owner) {
        Pairing p = requests.get(id);
        if (p == null) throw error(HttpStatus.GONE, "登录请求已过期或服务已重启，请重新发起");
        if (owner == null || !p.owner.equals(owner)) throw error(HttpStatus.FORBIDDEN, "登录请求不属于当前浏览器");
        return p;
    }

    private <K> void rate(Map<K, Deque<Instant>> limits, K key, int count, Instant now) {
        Instant cutoff = now.minusSeconds(60);
        limits.values().forEach(q -> { while (!q.isEmpty() && !q.peekFirst().isAfter(cutoff)) q.removeFirst(); });
        limits.values().removeIf(Deque::isEmpty);
        if (!limits.containsKey(key) && limits.size() >= CAPACITY) throw error(HttpStatus.TOO_MANY_REQUESTS, "请求过多，请稍后重试");
        Deque<Instant> attempts = limits.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        if (attempts.size() >= count) throw error(HttpStatus.TOO_MANY_REQUESTS, "操作太频繁，请一分钟后重试");
        attempts.addLast(now);
    }

    private ResponseStatusException error(HttpStatus status, String message) { return new ResponseStatusException(status, message); }
}
