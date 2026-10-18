package com.shifa.oms.courier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory, per-IP throttle for the public by-mobile order lookup
 * ({@code GET /api/track/by-mobile}) — anti-scraping for the unauthenticated
 * self-service tracking page.
 *
 * <p>Allows {@link #MAX_LOOKUPS} lookups per client IP within a rolling
 * {@link #WINDOW}; beyond that the caller is refused until the window elapses.
 * Mirrors {@link com.shifa.oms.auth.LoginRateLimiter}: process-local and
 * deliberately simple (correct for the single-instance deployment; move to a
 * shared store if ever scaled horizontally). Best-effort only.
 */
@Component
public class PublicTrackingRateLimiter {

    /** Lookups allowed per client IP within {@link #WINDOW}. */
    static final int MAX_LOOKUPS = 20;

    /** Rolling window over which {@link #MAX_LOOKUPS} is counted. */
    static final Duration WINDOW = Duration.ofMinutes(10);

    private final Clock clock;
    private final boolean enabled;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    @Autowired
    public PublicTrackingRateLimiter(
            @Value("${app.security.track-rate-limit.enabled:true}") boolean enabled) {
        this(Clock.systemUTC(), enabled);
    }

    /** Package-visible constructor for a fixed clock in tests (throttle enabled). */
    PublicTrackingRateLimiter(Clock clock) {
        this(clock, true);
    }

    PublicTrackingRateLimiter(Clock clock, boolean enabled) {
        this.clock = clock;
        this.enabled = enabled;
    }

    /**
     * Returns {@code true} if a lookup from {@code clientIp} is allowed right now,
     * counting it against the window; {@code false} once the limit is exceeded.
     * Always {@code true} when the throttle is disabled.
     */
    public boolean tryAcquire(String clientIp) {
        if (!enabled) {
            return true;
        }
        String key = clientIp == null ? "" : clientIp.trim();
        Instant now = clock.instant();
        Window w = windows.compute(key, (k, existing) -> {
            if (existing == null || Duration.between(existing.startedAt, now).compareTo(WINDOW) > 0) {
                return new Window(now);
            }
            return existing;
        });
        return w.count.incrementAndGet() <= MAX_LOOKUPS;
    }

    private static final class Window {
        private final Instant startedAt;
        private final java.util.concurrent.atomic.AtomicInteger count =
                new java.util.concurrent.atomic.AtomicInteger();

        private Window(Instant startedAt) {
            this.startedAt = startedAt;
        }
    }
}
