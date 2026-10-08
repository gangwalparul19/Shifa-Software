package com.shifa.oms.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory brute-force throttle for the login endpoint.
 *
 * <p>Tracks consecutive failed attempts per {@code username|clientIp} key. After
 * {@link #MAX_ATTEMPTS} failures within the window the key is locked out for
 * {@link #LOCKOUT} and further attempts are rejected with 429 until it expires.
 * A successful login clears the key immediately.
 *
 * <p>Deliberately simple and process-local — correct for the single-instance
 * deployment. If the app is ever scaled horizontally this must move to a shared
 * store (e.g. Redis) so the limit is enforced across instances.
 */
@Component
public class LoginRateLimiter {

    /** Consecutive failures before a key is locked out. */
    static final int MAX_ATTEMPTS = 5;

    /** How long a key stays locked once {@link #MAX_ATTEMPTS} is reached. */
    static final Duration LOCKOUT = Duration.ofMinutes(15);

    /** Idle failures older than this are forgotten (sliding reset). */
    static final Duration ATTEMPT_WINDOW = Duration.ofMinutes(15);

    private final Clock clock;
    private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();

    /**
     * Master on/off switch (default ON). Set {@code app.security.login-rate-limit.enabled=false}
     * to disable the throttle entirely — intended for the demo/sandbox box, where
     * frequent test logins (and the PWA's auto-retries) would otherwise trip the
     * lockout. Production leaves it ON.
     */
    private final boolean enabled;

    @Autowired
    public LoginRateLimiter(
            @Value("${app.security.login-rate-limit.enabled:true}") boolean enabled) {
        this(Clock.systemUTC(), enabled);
    }

    /** Package-visible constructor for a fixed clock in tests (throttle enabled). */
    LoginRateLimiter(Clock clock) {
        this(clock, true);
    }

    LoginRateLimiter(Clock clock, boolean enabled) {
        this.clock = clock;
        this.enabled = enabled;
    }

    /**
     * Throws 429 if the key is currently locked out. Call before verifying
     * credentials. Expired lockouts/windows are reset lazily here. A no-op when
     * the throttle is disabled.
     */
    public void checkNotLocked(String username, String clientIp) {
        if (!enabled) {
            return;
        }
        String key = key(username, clientIp);
        Attempts a = attempts.get(key);
        if (a == null) {
            return;
        }
        Instant now = clock.instant();
        if (a.lockedUntil != null) {
            if (now.isBefore(a.lockedUntil)) {
                long seconds = Duration.between(now, a.lockedUntil).toSeconds() + 1;
                throw new TooManyLoginAttemptsException(
                        "Too many failed login attempts. Try again in " + seconds + " seconds.");
            }
            // Lockout expired — forget the key entirely.
            attempts.remove(key);
            return;
        }
        // Drop a stale failure streak outside the sliding window.
        if (Duration.between(a.lastFailureAt, now).compareTo(ATTEMPT_WINDOW) > 0) {
            attempts.remove(key);
        }
    }

    /** Record a failed attempt; locks the key out once the threshold is hit. No-op when disabled. */
    public void recordFailure(String username, String clientIp) {
        if (!enabled) {
            return;
        }
        String key = key(username, clientIp);
        Instant now = clock.instant();
        Attempts a = attempts.computeIfAbsent(key, k -> new Attempts());
        a.lastFailureAt = now;
        int fails = a.count.incrementAndGet();
        if (fails >= MAX_ATTEMPTS) {
            a.lockedUntil = now.plus(LOCKOUT);
        }
    }

    /** Clear any failure state for the key on a successful login. */
    public void recordSuccess(String username, String clientIp) {
        attempts.remove(key(username, clientIp));
    }

    private static String key(String username, String clientIp) {
        String u = username == null ? "" : username.trim().toLowerCase();
        String ip = clientIp == null ? "" : clientIp.trim();
        return u + "|" + ip;
    }

    private static final class Attempts {
        private final AtomicInteger count = new AtomicInteger();
        private volatile Instant lastFailureAt = Instant.EPOCH;
        private volatile Instant lockedUntil;
    }
}
