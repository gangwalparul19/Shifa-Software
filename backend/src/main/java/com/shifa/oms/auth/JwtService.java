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
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Issues and validates JSON Web Tokens signed with HMAC-SHA256, using the secret
 * from {@link JwtProperties}. Kept dependency-free (JDK crypto + Jackson) so the
 * OMS does not pull in an extra JWT library.
 *
 * <p>Tokens carry the subject username ({@code sub}), user id ({@code uid}), role
 * ({@code role}), token type ({@code typ}) and standard {@code iat}/{@code exp}
 * timestamps. Access tokens are short-lived; refresh tokens live longer and are
 * accepted only by {@code /api/auth/refresh}. Validation checks the signature
 * (constant-time), the expiry, and — when requested — the token type.
 */
@Service
public class JwtService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    // Fixed JOSE header for HS256: {"alg":"HS256","typ":"JWT"}
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

    /** Issues a signed access token for the given user. */
    public String issueAccessToken(User user) {
        Instant now = clock.instant();
        return issue(user, TokenType.ACCESS, now, now.plus(properties.getAccessTokenTtl()));
    }

    /** Issues a signed refresh token for the given user. */
    public String issueRefreshToken(User user) {
        Instant now = clock.instant();
        return issue(user, TokenType.REFRESH, now, now.plus(properties.getRefreshTokenTtl()));
    }

    /** The configured access-token lifetime, in seconds (for the login response). */
    public long accessTokenTtlSeconds() {
        return properties.getAccessTokenTtl().toSeconds();
    }

    /**
     * Parses and fully validates a token, requiring it to be of {@code expectedType}.
     *
     * @throws InvalidTokenException if the token is malformed, its signature is
     *                               invalid, it is expired, or it is of the wrong type
     */
    public JwtClaims parse(String token, TokenType expectedType) {
        JwtClaims claims = parse(token);
        if (claims.type() != expectedType) {
            throw new InvalidTokenException("Expected a " + expectedType + " token");
        }
        return claims;
    }

    /**
     * Parses and validates a token's signature and expiry (any type).
     *
     * @throws InvalidTokenException if the token is malformed, its signature is
     *                               invalid, or it is expired
     */
    public JwtClaims parse(String token) {
        if (token == null || token.isBlank()) {
            throw new InvalidTokenException("Token is missing");
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new InvalidTokenException("Token structure is invalid");
        }
        String signingInput = parts[0] + "." + parts[1];
        String expectedSignature = sign(signingInput);
        if (!constantTimeEquals(expectedSignature, parts[2])) {
            throw new InvalidTokenException("Token signature is invalid");
        }

        Map<String, Object> payload = decodePayload(parts[1]);
        Instant expiresAt = readInstant(payload, "exp");
        if (expiresAt == null || !expiresAt.isAfter(clock.instant())) {
            throw new InvalidTokenException("Token has expired");
        }

        return new JwtClaims(
                readLong(payload, "uid"),
                readString(payload, "sub"),
                readRole(payload),
                readTokenType(payload),
                readInstant(payload, "iat"),
                expiresAt);
    }

    private String issue(User user, TokenType type, Instant issuedAt, Instant expiresAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sub", user.getUsername());
        payload.put("uid", user.getId());
        payload.put("role", user.getRole().name());
        payload.put("typ", type.name());
        payload.put("iat", issuedAt.getEpochSecond());
        payload.put("exp", expiresAt.getEpochSecond());

        String encodedPayload = base64Url(toJsonBytes(payload));
        String signingInput = ENCODED_HEADER + "." + encodedPayload;
        return signingInput + "." + sign(signingInput);
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
            byte[] json = Base64.getUrlDecoder().decode(encodedPayload);
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            throw new InvalidTokenException("Token payload is invalid");
        }
    }

    private String sign(String signingInput) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(properties.getSecret().getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] signature = mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign JWT", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static Long readLong(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new InvalidTokenException("Token claim '" + key + "' is invalid");
    }

    private static String readString(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value instanceof String s && !s.isBlank()) {
            return s;
        }
        throw new InvalidTokenException("Token claim '" + key + "' is invalid");
    }

    private static Instant readInstant(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value instanceof Number number) {
            return Instant.ofEpochSecond(number.longValue());
        }
        return null;
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
