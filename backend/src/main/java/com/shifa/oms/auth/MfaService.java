package com.shifa.oms.auth;

import com.shifa.oms.auth.dto.MfaChallengeResponse;
import com.shifa.oms.common.ApiException;
import com.shifa.oms.common.ResourceNotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * Orchestrates the TOTP (Google Authenticator) multi-factor flow, keeping all
 * MFA logic out of {@link AuthService}.
 *
 * <p>After a correct password, {@link #challengeFor(User)} decides what the user
 * owes:
 * <ul>
 *   <li>MFA not active (off, or on-but-not-enrolled is handled below) → the
 *       caller issues tokens as normal;</li>
 *   <li>enrolled → an {@code MFA_REQUIRED} challenge (prompt for a code);</li>
 *   <li>on but not enrolled → an {@code MFA_ENROLL} challenge carrying a fresh
 *       secret + QR provisioning URI, so the user enrolls before their first
 *       code — never locked out by the flag alone.</li>
 * </ul>
 *
 * <p>The second-factor step is completed by {@link #verifyLogin(String)} (an
 * enrolled user's code) or {@link #confirmEnrollment(String, String)} (the first
 * code, which also finalises enrollment). Both resolve the user from the signed
 * MFA ticket and leave token issuance to the caller.
 */
@Service
public class MfaService {

    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final TotpService totpService;
    private final Clock clock;

    /** Label shown in the authenticator app (the "issuer"). */
    private final String issuer;

    @Autowired
    public MfaService(UserRepository userRepository,
                      JwtService jwtService,
                      TotpService totpService,
                      @Value("${app.security.mfa.issuer:Shifa OMS}") String issuer) {
        this(userRepository, jwtService, totpService, issuer, Clock.systemUTC());
    }

    /** Package-visible constructor for a fixed clock in tests. */
    MfaService(UserRepository userRepository,
               JwtService jwtService,
               TotpService totpService,
               String issuer,
               Clock clock) {
        this.userRepository = userRepository;
        this.jwtService = jwtService;
        this.totpService = totpService;
        this.issuer = (issuer == null || issuer.isBlank()) ? "Shifa OMS" : issuer;
        this.clock = clock;
    }

    /**
     * Whether this user needs any MFA interaction at all (either a code or
     * enrollment). When false the caller issues tokens immediately.
     */
    public boolean mfaRequired(User user) {
        return user.isMfaEnabled();
    }

    /**
     * Builds the MFA challenge for a user whose password was just verified.
     * Precondition: {@code user.isMfaEnabled()} is true (callers check
     * {@link #mfaRequired(User)} first). Generates and persists a fresh secret
     * for a not-yet-enrolled user so the QR is stable across a page reload until
     * they confirm.
     */
    @Transactional
    public MfaChallengeResponse challengeFor(User user) {
        String ticket = jwtService.issueMfaTicket(user);
        if (user.isMfaEnrolled()) {
            return MfaChallengeResponse.required(ticket);
        }
        // On, but not enrolled yet: provision a secret now and return the QR.
        // Persisted (not yet confirmed) so re-posting login during enrollment
        // keeps the same secret the user may already be scanning.
        String secret = user.getMfaSecret();
        if (secret == null || secret.isBlank()) {
            secret = totpService.generateSecret();
            user.setMfaSecret(secret);
            user.setMfaEnrolledAt(null);
            userRepository.save(user);
        }
        String otpauthUri = totpService.buildOtpAuthUri(issuer, user.getUsername(), secret);
        return MfaChallengeResponse.enroll(ticket, otpauthUri, secret);
    }

    /**
     * Verifies an enrolled user's current code for the given login ticket and
     * returns the user so the caller can issue tokens. Rejects a mismatched code
     * (401) and a user who is not actually enrolled (409 — they must enroll).
     */
    @Transactional(readOnly = true)
    public User verifyLogin(String mfaTicket, String code) {
        User user = resolveTicketUser(mfaTicket);
        if (!user.isMfaEnrolled()) {
            throw new ApiException(HttpStatus.CONFLICT, "MFA_NOT_ENROLLED",
                    "Two-factor is not set up for this account yet.");
        }
        if (!totpService.verify(user.getMfaSecret(), code)) {
            throw invalidCode();
        }
        return user;
    }

    /**
     * Confirms the first code during enrollment: validates it against the
     * provisioned secret, stamps {@code mfaEnrolledAt}, and returns the user so
     * the caller can issue tokens. Rejects a wrong code (401) and a user with no
     * provisioned secret (409 — they must restart the login/enroll flow).
     */
    @Transactional
    public User confirmEnrollment(String mfaTicket, String code) {
        User user = resolveTicketUser(mfaTicket);
        String secret = user.getMfaSecret();
        if (secret == null || secret.isBlank()) {
            throw new ApiException(HttpStatus.CONFLICT, "MFA_NOT_PROVISIONED",
                    "No pending two-factor setup. Please sign in again to restart setup.");
        }
        if (!totpService.verify(secret, code)) {
            throw invalidCode();
        }
        user.setMfaEnrolledAt(LocalDateTime.now(clock));
        userRepository.save(user);
        return user;
    }

    /** Resolves and re-loads the user behind a valid MFA ticket (401 if stale/invalid). */
    private User resolveTicketUser(String mfaTicket) {
        JwtClaims claims = jwtService.parse(mfaTicket, TokenType.MFA);
        User user = userRepository.findById(claims.userId())
                .orElseThrow(() -> new ResourceNotFoundException("Token subject no longer exists"));
        if (!user.isActive()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "ACCOUNT_DISABLED", "This account is disabled.");
        }
        return user;
    }

    private static ApiException invalidCode() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_MFA_CODE",
                "That code is incorrect or expired. Try the current code from your authenticator app.");
    }
}
