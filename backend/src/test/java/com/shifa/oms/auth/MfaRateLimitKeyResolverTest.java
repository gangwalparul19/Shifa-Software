package com.shifa.oms.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MfaRateLimitKeyResolverTest {

    private static final Instant NOW = Instant.parse("2024-06-01T12:00:00Z");

    private final JwtService jwtService = jwtServiceAt(NOW);
    private final MfaRateLimitKeyResolver resolver = new MfaRateLimitKeyResolver(jwtService);

    @Test
    void validTicketsForDifferentUsersHaveIndependentBuckets() {
        String aliceTicket = jwtService.issueMfaTicket(user(42L, "alice"));
        String bobTicket = jwtService.issueMfaTicket(user(43L, "bob"));

        assertThat(resolver.resolve(aliceTicket)).isEqualTo("mfa-user:42");
        assertThat(resolver.resolve(bobTicket)).isEqualTo("mfa-user:43");
        assertThat(resolver.resolve(aliceTicket)).isEqualTo(resolver.resolve(aliceTicket));
    }

    @Test
    void invalidAndExpiredTicketsShareOneBoundedFallbackBucket() {
        String valid = jwtService.issueMfaTicket(user(42L, "alice"));
        String tampered = valid.substring(0, valid.length() - 1)
                + (valid.endsWith("a") ? "b" : "a");
        String expired = valid;

        assertThat(resolver.resolve("not-a-ticket")).isEqualTo(MfaRateLimitKeyResolver.INVALID_TICKET_KEY);
        assertThat(resolver.resolve(tampered)).isEqualTo(MfaRateLimitKeyResolver.INVALID_TICKET_KEY);
        assertThat(new MfaRateLimitKeyResolver(jwtServiceAt(NOW.plus(Duration.ofMinutes(6))))
                .resolve(expired)).isEqualTo(MfaRateLimitKeyResolver.INVALID_TICKET_KEY);
        assertThat(resolver.resolve("another-random-ticket"))
                .isEqualTo(resolver.resolve("not-a-ticket"));
    }

    @Test
    void limiterKeepsMfaUsersSeparateButSharesInvalidFallbackPerIp() {
        LoginRateLimiter limiter = new LoginRateLimiter(Clock.fixed(NOW, ZoneOffset.UTC));
        String alice = resolver.resolve(jwtService.issueMfaTicket(user(42L, "alice")));
        String bob = resolver.resolve(jwtService.issueMfaTicket(user(43L, "bob")));

        for (int i = 0; i < LoginRateLimiter.MAX_ATTEMPTS; i++) {
            limiter.recordFailure(alice, "203.0.113.9");
        }
        assertThatThrownBy(() -> limiter.checkNotLocked(alice, "203.0.113.9"))
                .isInstanceOf(TooManyLoginAttemptsException.class);
        limiter.checkNotLocked(bob, "203.0.113.9");

        String invalid = MfaRateLimitKeyResolver.INVALID_TICKET_KEY;
        for (int i = 0; i < LoginRateLimiter.MAX_ATTEMPTS; i++) {
            limiter.recordFailure(invalid, "198.51.100.7");
        }
        assertThatThrownBy(() -> limiter.checkNotLocked(invalid, "198.51.100.7"))
                .isInstanceOf(TooManyLoginAttemptsException.class);
    }

    private static JwtService jwtServiceAt(Instant instant) {
        JwtProperties properties = new JwtProperties();
        properties.setSecret("test-secret-that-is-long-enough-for-hmac-sha256-signing");
        properties.setMfaTicketTtl(Duration.ofMinutes(5));
        return new JwtService(properties, new ObjectMapper(), Clock.fixed(instant, ZoneOffset.UTC));
    }

    private static User user(long id, String username) {
        User user = new User(username, "hash", Role.SALESPERSON, username, true);
        try {
            var field = User.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(user, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return user;
    }
}
