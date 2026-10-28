package com.shifa.oms.auth;

import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * Time-based One-Time Password (TOTP, RFC 6238) support for MFA, compatible with
 * Google Authenticator / Authy and any other standard authenticator app.
 *
 * <p><strong>Dependency-free by design.</strong> TOTP is HMAC-SHA1 over a
 * 30-second time counter, truncated to a 6-digit code (RFC 4226 / RFC 6238).
 * The JDK's {@link Mac} already provides HMAC-SHA1 — the same approach the
 * {@link JwtService} uses for signing — so the OMS does not pull in an extra
 * OTP library. A small RFC 4648 Base32 codec is included for the shared secret
 * (authenticator apps exchange the key in Base32).
 *
 * <p>Verification accepts the current time step plus one step on either side
 * (a {@code ±TIME_STEP} window) to tolerate clock drift between the server and
 * the user's phone, and compares codes in constant time.
 */
@Service
public class TotpService {

    /** Code length (digits). Standard for authenticator apps. */
    static final int DIGITS = 6;

    /** Time step in seconds (standard = 30). */
    static final int TIME_STEP_SECONDS = 30;

    /**
     * How many steps on each side of "now" are accepted, to tolerate clock drift
     * between the server and the user's device. 1 = accept the previous, current,
     * and next 30-second code.
     */
    static final int DRIFT_STEPS = 1;

    /** Secret length in bytes (160 bits — RFC 4226 recommended minimum). */
    private static final int SECRET_BYTES = 20;

    private static final String HMAC_ALGORITHM = "HmacSHA1";
    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private final SecureRandom secureRandom = new SecureRandom();
    private final Clock clock;

    public TotpService() {
        this(Clock.systemUTC());
    }

    /** Package-visible constructor for a fixed clock in tests. */
    TotpService(Clock clock) {
        this.clock = clock;
    }

    /**
     * Generates a fresh, random Base32-encoded TOTP secret for a new enrollment.
     * The returned string is what both the server stores and the authenticator
     * app receives (via the QR code / manual entry).
     */
    public String generateSecret() {
        byte[] buffer = new byte[SECRET_BYTES];
        secureRandom.nextBytes(buffer);
        return base32Encode(buffer);
    }

    /**
     * Builds the {@code otpauth://totp/...} provisioning URI that an
     * authenticator app reads from a QR code. Example:
     * {@code otpauth://totp/Shifa%20OMS:alice?secret=XXXX&issuer=Shifa%20OMS&digits=6&period=30}.
     *
     * @param issuer      product/brand label shown in the app (e.g. "Shifa OMS")
     * @param accountName the user's identifier shown in the app (e.g. username)
     * @param secret      the Base32 secret from {@link #generateSecret()}
     */
    public String buildOtpAuthUri(String issuer, String accountName, String secret) {
        String encIssuer = urlEncode(issuer);
        String encAccount = urlEncode(accountName);
        String label = encIssuer + ":" + encAccount;
        return "otpauth://totp/" + label
                + "?secret=" + secret
                + "&issuer=" + encIssuer
                + "&algorithm=SHA1"
                + "&digits=" + DIGITS
                + "&period=" + TIME_STEP_SECONDS;
    }

    /**
     * Verifies a user-entered code against the secret, accepting the current time
     * step and {@link #DRIFT_STEPS} steps on each side. Non-numeric or wrong-length
     * input simply fails to match (no exception). Comparison is constant-time.
     *
     * @return {@code true} if the code is valid for the current time window
     */
    public boolean verify(String secret, String code) {
        if (secret == null || secret.isBlank() || code == null) {
            return false;
        }
        String trimmed = code.trim();
        if (trimmed.length() != DIGITS || !trimmed.chars().allMatch(Character::isDigit)) {
            return false;
        }
        byte[] key;
        try {
            key = base32Decode(secret);
        } catch (IllegalArgumentException e) {
            return false;
        }
        long currentStep = Math.floorDiv(clock.instant().getEpochSecond(), (long) TIME_STEP_SECONDS);
        for (long step = currentStep - DRIFT_STEPS; step <= currentStep + DRIFT_STEPS; step++) {
            String candidate = generateCode(key, step);
            if (constantTimeEquals(candidate, trimmed)) {
                return true;
            }
        }
        return false;
    }

    /** Computes the RFC 6238 6-digit code for a given key and time-step counter. */
    private String generateCode(byte[] key, long timeStep) {
        byte[] counter = new byte[8];
        long value = timeStep;
        for (int i = 7; i >= 0; i--) {
            counter[i] = (byte) (value & 0xff);
            value >>>= 8;
        }
        byte[] hash = hmacSha1(key, counter);
        // Dynamic truncation (RFC 4226 §5.3).
        int offset = hash[hash.length - 1] & 0x0f;
        int binary = ((hash[offset] & 0x7f) << 24)
                | ((hash[offset + 1] & 0xff) << 16)
                | ((hash[offset + 2] & 0xff) << 8)
                | (hash[offset + 3] & 0xff);
        int otp = binary % (int) Math.pow(10, DIGITS);
        return String.format("%0" + DIGITS + "d", otp);
    }

    private static byte[] hmacSha1(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute TOTP HMAC", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    // --- RFC 4648 Base32 (no padding on encode; tolerant of padding on decode) ---

    private static String base32Encode(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0;
        int bitsLeft = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                int index = (buffer >> (bitsLeft - 5)) & 0x1f;
                bitsLeft -= 5;
                sb.append(BASE32_ALPHABET.charAt(index));
            }
        }
        if (bitsLeft > 0) {
            int index = (buffer << (5 - bitsLeft)) & 0x1f;
            sb.append(BASE32_ALPHABET.charAt(index));
        }
        return sb.toString();
    }

    private static byte[] base32Decode(String encoded) {
        String cleaned = Objects.requireNonNull(encoded).trim()
                .replace("=", "").replace(" ", "").toUpperCase();
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("Empty Base32 secret");
        }
        int buffer = 0;
        int bitsLeft = 0;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(cleaned.length() * 5 / 8);
        for (int i = 0; i < cleaned.length(); i++) {
            int index = BASE32_ALPHABET.indexOf(cleaned.charAt(i));
            if (index < 0) {
                throw new IllegalArgumentException("Invalid Base32 character");
            }
            buffer = (buffer << 5) | index;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                out.write((buffer >> (bitsLeft - 8)) & 0xff);
                bitsLeft -= 8;
            }
        }
        return out.toByteArray();
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8)
                // otpauth labels use %20 for spaces, not '+'.
                .replace("+", "%20");
    }

    /** The current-step instant, exposed for tests. */
    Instant now() {
        return clock.instant();
    }

    /**
     * Computes the code for the current time step (package-visible, for tests).
     * Not used by production code paths, which call {@link #verify}.
     */
    String generateCodeForTest(String secret) {
        long step = Math.floorDiv(clock.instant().getEpochSecond(), (long) TIME_STEP_SECONDS);
        return generateCode(base32Decode(secret), step);
    }
}
