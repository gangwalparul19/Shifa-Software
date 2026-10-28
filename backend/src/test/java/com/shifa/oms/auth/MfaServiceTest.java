package com.shifa.oms.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shifa.oms.auth.dto.MfaChallengeResponse;
import com.shifa.oms.common.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the TOTP {@link MfaService} flow: enrollment challenge for an
 * enabled-but-not-enrolled user, enrollment confirmation, and the verify step
 * for an already-enrolled user. Uses a mocked {@link UserRepository} (interface,
 * safe to mock on this runtime), a real {@link JwtService} + {@link TotpService}
 * on a fixed clock.
 */
class MfaServiceTest {

    private static final Instant NOW = Instant.parse("2024-06-01T12:00:00Z");
    private static final Clock FIXED = Clock.fixed(NOW, ZoneOffset.UTC);

    private UserRepository userRepository;
    private JwtService jwtService;
    private TotpService totpService;
    private MfaService mfaService;

    @BeforeEach
    void setUp() {
        userRepository = Mockito.mock(UserRepository.class);
        JwtProperties properties = new JwtProperties();
        properties.setSecret("test-secret-that-is-long-enough-for-hmac-sha256-signing");
        jwtService = new JwtService(properties, new ObjectMapper(), FIXED);
        totpService = new TotpService(FIXED);
        mfaService = new MfaService(userRepository, jwtService, totpService, "Shifa OMS", FIXED);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private User userWithId(long id) {
        User user = new User("admin", "hash", Role.ADMIN, "Admin", true);
        try {
            var field = User.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(user, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return user;
    }

    @Test
    void enabledButNotEnrolledUserGetsAnEnrollChallengeWithSecret() {
        User user = userWithId(1L);
        user.setMfaEnabled(true);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        MfaChallengeResponse challenge = mfaService.challengeFor(user);

        assertThat(challenge.status()).isEqualTo(MfaChallengeResponse.STATUS_ENROLL);
        assertThat(challenge.mfaTicket()).isNotBlank();
        assertThat(challenge.secret()).isNotBlank();
        assertThat(challenge.otpauthUri()).startsWith("otpauth://totp/");
        // Secret was provisioned on the user but enrollment is NOT complete yet.
        assertThat(user.getMfaSecret()).isEqualTo(challenge.secret());
        assertThat(user.isMfaEnrolled()).isFalse();
    }

    @Test
    void confirmEnrollmentWithCorrectCodeMarksUserEnrolledAndReturnsUser() {
        User user = userWithId(1L);
        user.setMfaEnabled(true);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        MfaChallengeResponse enroll = mfaService.challengeFor(user);
        String code = totpService.generateCodeForTest(enroll.secret());

        User confirmed = mfaService.confirmEnrollment(enroll.mfaTicket(), code);

        assertThat(confirmed.getId()).isEqualTo(1L);
        assertThat(user.isMfaEnrolled()).isTrue();
        assertThat(user.getMfaEnrolledAt()).isNotNull();
    }

    @Test
    void enrolledUserGetsRequiredChallengeAndVerifiesWithCurrentCode() {
        User user = userWithId(1L);
        user.setMfaEnabled(true);
        user.setMfaSecret(totpService.generateSecret());
        user.setMfaEnrolledAt(java.time.LocalDateTime.now(FIXED));
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        MfaChallengeResponse challenge = mfaService.challengeFor(user);
        assertThat(challenge.status()).isEqualTo(MfaChallengeResponse.STATUS_REQUIRED);
        assertThat(challenge.secret()).isNull();
        assertThat(challenge.otpauthUri()).isNull();

        String code = totpService.generateCodeForTest(user.getMfaSecret());
        User verified = mfaService.verifyLogin(challenge.mfaTicket(), code);
        assertThat(verified.getId()).isEqualTo(1L);
    }

    @Test
    void verifyWithWrongCodeIsRejectedWith401() {
        User user = userWithId(1L);
        user.setMfaEnabled(true);
        user.setMfaSecret(totpService.generateSecret());
        user.setMfaEnrolledAt(java.time.LocalDateTime.now(FIXED));
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        String ticket = jwtService.issueMfaTicket(user);
        assertThatThrownBy(() -> mfaService.verifyLogin(ticket, "000000"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus().value()).isEqualTo(401));
    }

    @Test
    void verifyRejectsATicketOfTheWrongType() {
        User user = userWithId(1L);
        // An access token is not an MFA ticket — parse(TokenType.MFA) must reject it.
        String accessToken = jwtService.issueAccessToken(user);
        assertThatThrownBy(() -> mfaService.verifyLogin(accessToken, "123456"))
                .isInstanceOf(InvalidTokenException.class);
    }
}
