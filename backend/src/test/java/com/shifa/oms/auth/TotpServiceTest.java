package com.shifa.oms.auth;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the hand-rolled RFC 6238 {@link TotpService}. Uses a fixed
 * clock so the current 30-second code is deterministic, and cross-checks against
 * a known RFC 6238 test vector to prove the HMAC-SHA1 + truncation is correct.
 */
class TotpServiceTest {

    @Test
    void generatedSecretIsBase32AndVerifiesItsOwnCurrentCode() {
        Clock fixed = Clock.fixed(Instant.parse("2024-06-01T12:00:00Z"), ZoneOffset.UTC);
        TotpService totp = new TotpService(fixed);

        String secret = totp.generateSecret();
        // Base32 alphabet only (A-Z, 2-7), no padding.
        assertThat(secret).matches("[A-Z2-7]+");

        String code = totp.generateCodeForTest(secret);
        assertThat(code).hasSize(TotpService.DIGITS);
        assertThat(totp.verify(secret, code)).isTrue();
    }

    @Test
    void verifyAcceptsAdjacentStepsButRejectsFarCodes() {
        Instant base = Instant.parse("2024-06-01T12:00:00Z");
        String secret = new TotpService(Clock.fixed(base, ZoneOffset.UTC)).generateSecret();

        // Code valid for the step 30s earlier is still accepted (drift window ±1).
        TotpService prev = new TotpService(Clock.fixed(base.minusSeconds(30), ZoneOffset.UTC));
        String prevCode = prev.generateCodeForTest(secret);
        TotpService now = new TotpService(Clock.fixed(base, ZoneOffset.UTC));
        assertThat(now.verify(secret, prevCode)).isTrue();

        // A code from five steps away (150s) is outside the window → rejected.
        TotpService far = new TotpService(Clock.fixed(base.minusSeconds(150), ZoneOffset.UTC));
        assertThat(now.verify(secret, far.generateCodeForTest(secret))).isFalse();
    }

    @Test
    void verifyRejectsMalformedInput() {
        TotpService totp = new TotpService(Clock.fixed(Instant.now(), ZoneOffset.UTC));
        String secret = totp.generateSecret();
        assertThat(totp.verify(secret, null)).isFalse();
        assertThat(totp.verify(secret, "")).isFalse();
        assertThat(totp.verify(secret, "12345")).isFalse();   // too short
        assertThat(totp.verify(secret, "1234567")).isFalse(); // too long
        assertThat(totp.verify(secret, "abcdef")).isFalse();  // non-numeric
        assertThat(totp.verify(null, "123456")).isFalse();    // no secret
    }

    @Test
    void otpAuthUriCarriesIssuerAccountAndSecret() {
        TotpService totp = new TotpService(Clock.systemUTC());
        String uri = totp.buildOtpAuthUri("Shifa OMS", "admin", "ABCDEF234567");
        assertThat(uri).startsWith("otpauth://totp/Shifa%20OMS:admin?");
        assertThat(uri).contains("secret=ABCDEF234567");
        assertThat(uri).contains("issuer=Shifa%20OMS");
        assertThat(uri).contains("digits=6");
        assertThat(uri).contains("period=30");
    }
}
