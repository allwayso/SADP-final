package edu.sadp.checkcollect.identity;

import static org.assertj.core.api.Assertions.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class MiniWechatPairingsTest {
    static class Time extends Clock {
        Instant now = Instant.parse("2026-11-12T00:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
        void advance(long seconds) { now = now.plusSeconds(seconds); }
    }
    final Time clock = new Time();
    final MiniWechatPairings service = new MiniWechatPairings(clock);
    final UUID user = UUID.randomUUID();
    void rejects(Runnable action, int status) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class,
            error -> assertThat(error.getStatusCode().value()).isEqualTo(status));
    }
    @Test void ownerAndOneTimeApprovalAreRequired() {
        var p = service.create("browser");
        assertThat(p.code()).matches("[0-9]{8}");
        assertThat(p.id()).hasSize(43);
        rejects(() -> service.consume(p.id(), "browser"), 409);
        rejects(() -> service.status(p.id(), "other"), 403);
        service.confirm(p.code(), user);
        assertThat(service.status(p.id(), "browser")).isEqualTo("approved");
        rejects(() -> service.confirm(p.code(), user), 409);
        rejects(() -> service.consume(p.id(), "other"), 403);
        assertThat(service.consume(p.id(), "browser")).isEqualTo(user);
        rejects(() -> service.consume(p.id(), "browser"), 409);
    }
    @Test void expiryAndCancellationRejectConfirmationAndConsumption() {
        var expired = service.create("old");
        clock.advance(300);
        assertThat(service.status(expired.id(), "old")).isEqualTo("expired");
        rejects(() -> service.confirm(expired.code(), user), 410);
        rejects(() -> service.consume(expired.id(), "old"), 410);
        var cancelled = service.create("cancelled");
        service.cancel(cancelled.id(), "cancelled");
        rejects(() -> service.confirm(cancelled.code(), user), 409);
        var replaced = service.create("same-browser");
        service.create("same-browser");
        rejects(() -> service.confirm(replaced.code(), user), 409);
    }
    @Test void creationAndGuessingAreRateLimitedAndRecoverAfterWindow() {
        for (int i = 0; i < 3; i++) service.create("browser");
        rejects(() -> service.create("browser"), 429);
        for (int i = 0; i < 5; i++) rejects(() -> service.confirm("invalid", user), 400);
        rejects(() -> service.confirm("00000000", user), 429);
        clock.advance(60);
        assertThat(service.create("browser")).isNotNull();
        rejects(() -> service.confirm("invalid", user), 400);
    }
    @Test void storageIsBoundedAndExpiredEntriesCanBeReclaimed() {
        for (int i = 0; i < 1000; i++) service.create("browser-" + i);
        rejects(() -> service.create("overflow"), 429);
        clock.advance(300);
        assertThat(service.create("after-expiry")).isNotNull();
    }
    @Test void concurrentConsumersHaveOnlyOneWinner() throws Exception {
        var p = service.create("browser");
        service.confirm(p.code(), user);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            var tasks = new ArrayList<Callable<Boolean>>();
            for (int i = 0; i < 8; i++) tasks.add(() -> {
                try { service.consume(p.id(), "browser"); return true; }
                catch (ResponseStatusException e) { assertThat(e.getStatusCode().value()).isEqualTo(409); return false; }
            });
            int winners = 0;
            for (var result : pool.invokeAll(tasks)) if (result.get()) winners++;
            assertThat(winners).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }
}
