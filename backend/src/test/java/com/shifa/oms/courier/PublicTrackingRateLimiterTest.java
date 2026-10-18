package com.shifa.oms.courier;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PublicTrackingRateLimiter} — the per-IP anti-scraping
 * throttle on the public by-mobile order lookup. Uses a mutable fixed clock so
 * the rolling window can be advanced deterministically.
 */
class PublicTrackingRateLimiterTest {

    private static final String IP = "203.0.113.7";

    /** Allows exactly {@link PublicTrackingRateLimiter#MAX_LOOKUPS}, blocks the next. */
    @Test
    void allowsUpToTheLimitThenBlocks() {
        PublicTrackingRateLimiter limiter = new PublicTrackingRateLimiter(fixed(Instant.EPOCH));
        for (int i = 0; i < PublicTrackingRateLimiter.MAX_LOOKUPS; i++) {
            assertThat(limiter.tryAcquire(IP)).as("lookup %d", i).isTrue();
        }
        assertThat(limiter.tryAcquire(IP)).isFalse();
    }

    /** A different IP has its own independent budget. */
    @Test
    void separateIpsAreIndependent() {
        PublicTrackingRateLimiter limiter = new PublicTrackingRateLimiter(fixed(Instant.EPOCH));
        for (int i = 0; i < PublicTrackingRateLimiter.MAX_LOOKUPS; i++) {
            limiter.tryAcquire(IP);
        }
        assertThat(limiter.tryAcquire(IP)).isFalse();
        assertThat(limiter.tryAcquire("198.51.100.1")).isTrue();
    }

    /** Once the window elapses the IP's budget resets. */
    @Test
    void resetsAfterWindowElapses() {
        MutableClock clock = new MutableClock(Instant.EPOCH);
        PublicTrackingRateLimiter limiter = new PublicTrackingRateLimiter(clock);
        for (int i = 0; i < PublicTrackingRateLimiter.MAX_LOOKUPS; i++) {
            limiter.tryAcquire(IP);
        }
        assertThat(limiter.tryAcquire(IP)).isFalse();

        clock.advanceTo(Instant.EPOCH.plus(PublicTrackingRateLimiter.WINDOW).plusSeconds(1));
        assertThat(limiter.tryAcquire(IP)).isTrue();
    }

    /** When disabled, the throttle never blocks. */
    @Test
    void disabledNeverBlocks() {
        PublicTrackingRateLimiter limiter = new PublicTrackingRateLimiter(fixed(Instant.EPOCH), false);
        for (int i = 0; i < PublicTrackingRateLimiter.MAX_LOOKUPS * 3; i++) {
            assertThat(limiter.tryAcquire(IP)).isTrue();
        }
    }

    private static Clock fixed(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    /** A clock whose instant can be moved forward within a test. */
    private static final class MutableClock extends Clock {
        private volatile Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        private void advanceTo(Instant instant) {
            this.now = instant;
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
