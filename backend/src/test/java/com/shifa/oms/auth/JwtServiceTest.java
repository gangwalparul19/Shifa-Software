package com.shifa.oms.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link JwtService}: issuance/validation round-trips and the
 * rejection cases (wrong type, expiry, tampering) that back the 401 behavior of
 * Req 5.2.
 */
class JwtServiceTest {

    private static final Instant NOW = Instant.parse("2024-06-01T12:00:00Z");

    private final JwtProperties properties = properties();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private JwtProperties properties() {
        JwtProperties p = new JwtProperties();
        p.setSecret("test-secret-that-is-long-enough-for-hmac-sha256-signing");
        p.setAccessTokenTtl(Duration.ofMinutes(15));
        p.setRefreshTokenTtl(Duration.ofDays(7));
        return p;
    }

    private JwtService serviceAt(Instant instant) {
        return new JwtService(properties, objectMapper, Clock.fixed(instant, ZoneOffset.UTC));
    }

    private User user() {
        User user = new User("alice", "irrelevant-hash", Role.SALESPERSON, "Alice Sales", true);
        setId(user, 42L);
        return user;
    }

    private static void setId(User user, long id) {
        try {
            var field = User.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(user, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void accessTokenRoundTripsToItsClaims() {
        JwtService service = serviceAt(NOW);
        String token = service.issueAccessToken(user());

        JwtClaims claims = service.parse(token, TokenType.ACCESS);

        assertThat(claims.userId()).isEqualTo(42L);
        assertThat(claims.username()).isEqualTo("alice");
        assertThat(claims.role()).isEqualTo(Role.SALESPERSON);
        assertThat(claims.type()).isEqualTo(TokenType.ACCESS);
        assertThat(claims.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
        assertThat(claims.toPrincipal()).isEqualTo(new AuthPrincipal(42L, "alice", Role.SALESPERSON));
    }

    @Test
    void refreshTokenIsRejectedWhereAnAccessTokenIsExpected() {
        JwtService service = serviceAt(NOW);
        String refresh = service.issueRefreshToken(user());

        assertThatThrownBy(() -> service.parse(refresh, TokenType.ACCESS))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void expiredTokenIsRejected() {
        String token = serviceAt(NOW).issueAccessToken(user());

        JwtService later = serviceAt(NOW.plus(Duration.ofMinutes(16)));
        assertThatThrownBy(() -> later.parse(token, TokenType.ACCESS))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void tamperedTokenIsRejected() {
        JwtService service = serviceAt(NOW);
        String token = service.issueAccessToken(user());
        String tampered = token.substring(0, token.length() - 2)
                + (token.endsWith("aa") ? "bb" : "aa");

        assertThatThrownBy(() -> service.parse(tampered, TokenType.ACCESS))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void tokenSignedWithADifferentSecretIsRejected() {
        String token = serviceAt(NOW).issueAccessToken(user());

        JwtProperties other = properties();
        other.setSecret("a-completely-different-secret-value-for-verification");
        JwtService otherService = new JwtService(other, objectMapper, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> otherService.parse(token, TokenType.ACCESS))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void malformedTokensAreRejected() {
        JwtService service = serviceAt(NOW);
        assertThatThrownBy(() -> service.parse("not-a-jwt", TokenType.ACCESS))
                .isInstanceOf(InvalidTokenException.class);
        assertThatThrownBy(() -> service.parse("", TokenType.ACCESS))
                .isInstanceOf(InvalidTokenException.class);
        assertThatThrownBy(() -> service.parse(null, TokenType.ACCESS))
                .isInstanceOf(InvalidTokenException.class);
    }
}
