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
        User alice = user();
        String token = service.issueAccessToken(alice);

        JwtClaims claims = service.parse(token, TokenType.ACCESS);

        assertThat(claims.sessionVersion()).isEqualTo(0L);
        alice.incrementSessionVersion();
        JwtClaims revokedReplacement = service.parse(service.issueAccessToken(alice), TokenType.ACCESS);
        assertThat(revokedReplacement.sessionVersion()).isEqualTo(1L);
        assertThat(claims.userId()).isEqualTo(42L);
        assertThat(claims.role()).isEqualTo(Role.SALESPERSON);
        assertThat(claims.type()).isEqualTo(TokenType.ACCESS);
        assertThat(claims.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
        assertThat(claims.toPrincipal()).isEqualTo(new AuthPrincipal(42L, "alice", Role.SALESPERSON,
                claims.sessionStartedAt(), claims.sessionId()));
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

    @Test
    void tokensCarryAReusableSessionIdAndIdleHint() {
        JwtService service = serviceAt(NOW);
        User alice = user();

        JwtClaims access = service.parse(service.issueAccessToken(alice), TokenType.ACCESS);
        JwtClaims refresh = service.parse(service.issueRefreshToken(alice), TokenType.REFRESH);
        JwtClaims mfa = service.parse(service.issueMfaTicket(alice), TokenType.MFA);

        assertThat(access.sessionId()).isNotBlank();
        assertThat(refresh.sessionId()).isNotBlank();
        assertThat(mfa.sessionId()).isNotBlank();
        assertThat(access.sessionExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(30)));
        assertThat(refresh.sessionExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(30)));
        assertThat(mfa.sessionExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(30)));
    }

    @Test
    void refreshedTokensCanReuseTheSameSessionIdWithoutAnAbsoluteCap() {
        JwtService initial = serviceAt(NOW);
        JwtClaims original = initial.parse(initial.issueRefreshToken(user()), TokenType.REFRESH);
        JwtService later = serviceAt(NOW.plus(Duration.ofMinutes(10)));

        JwtClaims refreshed = later.parse(
                later.issueRefreshToken(user(), original.sessionId()), TokenType.REFRESH);

        assertThat(refreshed.sessionId()).isEqualTo(original.sessionId());
        assertThat(refreshed.sessionExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(10)).plus(Duration.ofMinutes(30)));
    }

    @Test
    void tokensWithoutSessionClaimsRemainParseableForSafeLegacyHandling() throws Exception {
        String legacy = legacyToken(serviceAt(NOW).issueRefreshToken(user()), properties.getSecret());

        JwtClaims claims = serviceAt(NOW.plus(Duration.ofMinutes(29))).parse(legacy, TokenType.REFRESH);
        assertThat(claims.sessionId()).isNotBlank();
        assertThat(claims.sessionStartedAt()).isNull();
        assertThat(claims.sessionExpiresAt()).isNull();
    }

    private String legacyToken(String token, String secret) throws Exception {
        String[] parts = token.split("\\.");
        byte[] payloadBytes = java.util.Base64.getUrlDecoder().decode(parts[1]);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> payload = objectMapper.readValue(payloadBytes, java.util.Map.class);
        payload.remove("session_start");
        payload.remove("session_exp");
        String payloadPart = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(objectMapper.writeValueAsBytes(payload));
        String signingInput = parts[0] + "." + payloadPart;
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal(signingInput.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return signingInput + "." + signature;
    }
}
