package com.shifa.oms.auth;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoginRateLimiterTest {

    private static final Instant NOW = Instant.parse("2024-06-01T12:00:00Z");
    private static final String IP = "203.0.113.9";

    @Test
    void locksAfterFiveFailuresAndKeepsKeysIndependent() {
        LoginRateLimiter limiter = new LoginRateLimiter(fixed(NOW));
        for (int i = 0; i < LoginRateLimiter.MAX_ATTEMPTS; i++) {
            limiter.recordFailure("alice", IP);
        }

        assertThatThrownBy(() -> limiter.checkNotLocked("alice", IP))
                .isInstanceOf(TooManyLoginAttemptsException.class);
        limiter.checkNotLocked("bob", IP);
        limiter.checkNotLocked("alice", "198.51.100.4");
    }

    @Test
    void successClearsOnlyThatUsernameAndIpBucket() {
        LoginRateLimiter limiter = new LoginRateLimiter(fixed(NOW));
        for (int i = 0; i < LoginRateLimiter.MAX_ATTEMPTS; i++) {
            limiter.recordFailure("alice", IP);
            limiter.recordFailure("bob", IP);
        }

        limiter.recordSuccess("alice", IP);
        limiter.checkNotLocked("alice", IP);
        assertThatThrownBy(() -> limiter.checkNotLocked("bob", IP))
                .isInstanceOf(TooManyLoginAttemptsException.class);
    }

    @Test
    void disabledLimiterNeverBlocks() {
        LoginRateLimiter limiter = new LoginRateLimiter(fixed(NOW), false);
        for (int i = 0; i < LoginRateLimiter.MAX_ATTEMPTS * 2; i++) {
            limiter.recordFailure("alice", IP);
        }
        limiter.checkNotLocked("alice", IP);
    }

    private static Clock fixed(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }
}
