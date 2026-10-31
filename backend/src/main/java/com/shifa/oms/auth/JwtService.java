package com.shifa.oms.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Issues and validates HS256 JWTs. JWT expiry remains an upper bound only;
 * authenticated-session acceptance is server-authoritative in AuthSessionService
 * and uses the signed opaque sid claim to apply the sliding idle timeout.
 */
@Service
public class JwtService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String ENCODED_HEADER =
            base64Url("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));

    private final JwtProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public JwtService(JwtProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, Clock.systemUTC());
    }

    /** Package-visible constructor allowing a fixed clock in tests. */
    JwtService(JwtProperties properties, ObjectMapper objectMapper, Clock clock) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public String issueAccessToken(User user) {
        return issueAccessToken(user, newSessionId());
    }

    /** Issues an access token for an existing server-side session. */
    public String issueAccessToken(User user, String sessionId) {
        return issue(user, TokenType.ACCESS, sessionId, now().plus(properties.getSessionTtl()),
                now().plus(properties.getAccessTokenTtl()));
    }

    /** Compatibility overload for callers that supplied the former session-start value. */
    public String issueAccessToken(User user, Instant ignoredSessionStart) {
        return issueAccessToken(user, newSessionId());
    }

    /** Issues an access token with the current server-calculated idle hint. */
    public String issueAccessToken(User user, String sessionId, Instant idleExpiresAt) {
        return issue(user, TokenType.ACCESS, sessionId, idleExpiresAt,
                now().plus(properties.getAccessTokenTtl()));
    }

    public String issueRefreshToken(User user) {
        return issueRefreshToken(user, newSessionId());
    }

    /** Issues a refresh token for an existing server-side session. */
    public String issueRefreshToken(User user, String sessionId) {
        return issue(user, TokenType.REFRESH, sessionId, now().plus(properties.getSessionTtl()),
                now().plus(properties.getRefreshTokenTtl()));
    }

    /** Compatibility overload for callers that supplied the former session-start value. */
    public String issueRefreshToken(User user, Instant ignoredSessionStart) {
        return issueRefreshToken(user, newSessionId());
    }

    public String issueRefreshToken(User user, String sessionId, Instant idleExpiresAt) {
        return issue(user, TokenType.REFRESH, sessionId, idleExpiresAt,
                now().plus(properties.getRefreshTokenTtl()));
    }

    public String issueMfaTicket(User user) {
        return issueMfaTicket(user, newSessionId());
    }

    /** Issues an MFA ticket carrying the session sid created after password verification. */
    public String issueMfaTicket(User user, String sessionId) {
        return issue(user, TokenType.MFA, sessionId, now().plus(properties.getSessionTtl()),
                now().plus(properties.getMfaTicketTtl()));
    }

    /** Compatibility overload for callers that supplied the former session-start value. */
    public String issueMfaTicket(User user, Instant ignoredSessionStart) {
        return issueMfaTicket(user, newSessionId());
    }

    public String issueMfaTicket(User user, String sessionId, Instant idleExpiresAt) {
        return issue(user, TokenType.MFA, sessionId, idleExpiresAt,
                now().plus(properties.getMfaTicketTtl()));
    }

    /** Current signing clock instant, truncated to JWT's second precision. */
    Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.SECONDS);
    }

    /** Compatibility helper retained for callers; this is now an idle hint only. */
    Instant sessionExpiresAt(Instant ignoredSessionStart) {
        return now().plus(properties.getSessionTtl());
    }

    /** Epoch seconds for a server idle deadline. */
    public long sessionExpiresAtEpochSeconds(Instant idleExpiresAt) {
        return idleExpiresAt.getEpochSecond();
    }

    public long accessTokenTtlSeconds() {
        return properties.getAccessTokenTtl().toSeconds();
    }

    public JwtClaims parse(String token, TokenType expectedType) {
        JwtClaims claims = parse(token);
        if (claims.type() != expectedType) {
            throw new InvalidTokenException("Expected a " + expectedType + " token");
        }
        return claims;
    }

    /** Parses signature and normal JWT expiry. Idle acceptance is checked by the session table. */
    public JwtClaims parse(String token) {
        if (token == null || token.isBlank()) {
            throw new InvalidTokenException("Token is missing");
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new InvalidTokenException("Token structure is invalid");
        }
        String signingInput = parts[0] + "." + parts[1];
        if (!constantTimeEquals(sign(signingInput), parts[2])) {
            throw new InvalidTokenException("Token signature is invalid");
        }
        Map<String, Object> payload = decodePayload(parts[1]);
        Instant now = now();
        Instant expiresAt = readInstant(payload, "exp");
        if (expiresAt == null || !expiresAt.isAfter(now)) {
            throw new InvalidTokenException("Token has expired");
        }
        return new JwtClaims(
                readLong(payload, "uid"),
                readString(payload, "sub"),
                readRole(payload),
                readOptionalLong(payload, "sv"),
                readTokenType(payload),
                readInstant(payload, "iat"),
                expiresAt,
                readInstant(payload, "session_start"),
                readInstant(payload, "session_exp"),
                readOptionalString(payload, "sid"));
    }

    private String issue(User user, TokenType type, String sessionId, Instant idleExpiresAt,
                         Instant requestedExpiry) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new InvalidTokenException("Authenticated token session id is missing");
        }
        Instant issuedAt = now();
        Instant expiresAt = requestedExpiry;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sub", user.getUsername());
        payload.put("uid", user.getId());
        payload.put("role", user.getRole().name());
        payload.put("sv", user.getSessionVersion());
        payload.put("sid", sessionId);
        String fullName = user.getFullName();
        if (fullName != null && !fullName.isBlank()) {
            payload.put("name", fullName.trim());
        }
        if (user.isMustChangePassword()) {
            payload.put("pwd", true);
        }
        payload.put("typ", type.name());
        payload.put("iat", issuedAt.getEpochSecond());
        payload.put("exp", expiresAt.getEpochSecond());
        // Retained as a signed client hint for compatibility. The server never
        // accepts or rejects activity from these claims.
        payload.put("session_start", issuedAt.getEpochSecond());
        payload.put("session_exp", idleExpiresAt.getEpochSecond());

        String encodedPayload = base64Url(toJsonBytes(payload));
        String signingInput = ENCODED_HEADER + "." + encodedPayload;
        return signingInput + "." + sign(signingInput);
    }

    private static String newSessionId() {
        return UUID.randomUUID().toString();
    }

    private byte[] toJsonBytes(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsBytes(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize JWT payload", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> decodePayload(String encodedPayload) {
        try {
            return objectMapper.readValue(Base64.getUrlDecoder().decode(encodedPayload), Map.class);
        } catch (Exception e) {
            throw new InvalidTokenException("Token payload is invalid");
        }
    }

    private String sign(String signingInput) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(properties.getSecret().getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign JWT", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static Long readLong(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value instanceof Number number) return number.longValue();
        throw new InvalidTokenException("Token claim '" + key + "' is invalid");
    }

    private static long readOptionalLong(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value == null) return 0L;
        if (value instanceof Number number && number.longValue() >= 0) return number.longValue();
        throw new InvalidTokenException("Token claim '" + key + "' is invalid");
    }

    private static String readString(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value instanceof String s && !s.isBlank()) return s;
        throw new InvalidTokenException("Token claim '" + key + "' is invalid");
    }

    private static String readOptionalString(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    private static Instant readInstant(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value instanceof Number number ? Instant.ofEpochSecond(number.longValue()) : null;
    }

    private static Role readRole(Map<String, Object> payload) {
        try {
            return Role.valueOf(readString(payload, "role"));
        } catch (IllegalArgumentException e) {
            throw new InvalidTokenException("Token role claim is invalid");
        }
    }

    private static TokenType readTokenType(Map<String, Object> payload) {
        try {
            return TokenType.valueOf(readString(payload, "typ"));
        } catch (IllegalArgumentException e) {
            throw new InvalidTokenException("Token type claim is invalid");
        }
    }
}
