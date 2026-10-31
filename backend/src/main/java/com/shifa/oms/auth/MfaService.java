package com.shifa.oms.auth;

import com.shifa.oms.audit.AuditActions;
import com.shifa.oms.audit.AuditService;
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

/** TOTP enrollment and verification with encrypted-at-rest secret handling. */
@Service
public class MfaService {

    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final TotpService totpService;
    private final Clock clock;
    private final String issuer;
    private final MfaSecretCrypto crypto;
    private final AuditService auditService;
    private final AuthSessionService authSessionService;

    @Autowired
    public MfaService(UserRepository userRepository,
                      JwtService jwtService,
                      TotpService totpService,
                      @Value("${app.security.mfa.issuer:Shifa OMS}") String issuer,
                      MfaSecretCrypto crypto,
                      AuditService auditService,
                      AuthSessionService authSessionService) {
        this(userRepository, jwtService, totpService, issuer, Clock.systemUTC(), crypto, auditService,
                authSessionService);
    }

    /** Backward-compatible fixed-clock constructor used by unit tests. */
    MfaService(UserRepository userRepository,
               JwtService jwtService,
               TotpService totpService,
               String issuer,
               Clock clock) {
        this(userRepository, jwtService, totpService, issuer, clock,
                new MfaSecretCrypto("test-mfa-key-that-is-at-least-32-bytes-long"), null, null);
    }

    /** Compatibility constructor for existing package tests and integrations. */
    MfaService(UserRepository userRepository,
               JwtService jwtService,
               TotpService totpService,
               String issuer,
               Clock clock,
               MfaSecretCrypto crypto,
               AuditService auditService) {
        this(userRepository, jwtService, totpService, issuer, clock, crypto, auditService, null);
    }

    MfaService(UserRepository userRepository,
               JwtService jwtService,
               TotpService totpService,
               String issuer,
               Clock clock,
               MfaSecretCrypto crypto,
               AuditService auditService,
               AuthSessionService authSessionService) {
        this.userRepository = userRepository;
        this.jwtService = jwtService;
        this.totpService = totpService;
        this.issuer = issuer == null || issuer.isBlank() ? "Shifa OMS" : issuer;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.crypto = crypto;
        this.auditService = auditService;
        this.authSessionService = authSessionService;
    }

    public boolean mfaRequired(User user) {
        return user.isMfaEnabled();
    }

    @Transactional
    public MfaChallengeResponse challengeFor(User user) {
        AuthSessionService.SessionHandle session = authSessionService == null ? null : authSessionService.create(user);
        String ticket = session == null
                ? jwtService.issueMfaTicket(user)
                : jwtService.issueMfaTicket(user, session.sessionId(), session.idleExpiresAt());
        if (user.isMfaEnrolled()) {
            // This also lazily converts V100 plaintext rows to ciphertext.
            String secret = secretFor(user);
            if (secret == null || secret.isBlank()) {
                throw new IllegalStateException("MFA secret is unavailable");
            }
            return MfaChallengeResponse.required(ticket);
        }

        String secret = user.getMfaSecret();
        if ((secret == null || secret.isBlank())
                && user.getMfaSecretCiphertext() != null
                && !user.getMfaSecretCiphertext().isBlank()) {
            secret = secretFor(user);
        }
        if (secret == null || secret.isBlank()) {
            secret = totpService.generateSecret();
            storeEncrypted(user, secret);
            user.setMfaEnrolledAt(null);
            userRepository.save(user);
            audit(AuditActions.USER_MFA_ENROLLMENT_STARTED, user,
                    "Started two-factor enrollment");
        } else {
            storeEncryptedIfLegacy(user, secret);
        }
        String otpauthUri = totpService.buildOtpAuthUri(issuer, user.getUsername(), secret);
        return MfaChallengeResponse.enroll(ticket, otpauthUri, secret);
    }

    @Transactional
    public User verifyLogin(String mfaTicket, String code) {
        User user = resolveTicketUser(mfaTicket);
        if (!user.isMfaEnrolled()) {
            throw new ApiException(HttpStatus.CONFLICT, "MFA_NOT_ENROLLED",
                    "Two-factor is not set up for this account yet.");
        }
        String secret = secretFor(user);
        if (!totpService.verify(secret, code)) {
            audit(AuditActions.USER_MFA_FAILED, user, "Failed two-factor verification");
            throw invalidCode();
        }
        return user;
    }

    @Transactional
    public User confirmEnrollment(String mfaTicket, String code) {
        User user = resolveTicketUser(mfaTicket);
        String secret = secretFor(user);
        if (secret == null || secret.isBlank()) {
            throw new ApiException(HttpStatus.CONFLICT, "MFA_NOT_PROVISIONED",
                    "No pending two-factor setup. Please sign in again to restart setup.");
        }
        if (!totpService.verify(secret, code)) {
            audit(AuditActions.USER_MFA_FAILED, user, "Failed two-factor enrollment confirmation");
            throw invalidCode();
        }
        user.setMfaEnrolledAt(LocalDateTime.now(clock));
        user.incrementSessionVersion();
        userRepository.save(user);
        audit(AuditActions.USER_MFA_ENROLLMENT_COMPLETED, user,
                "Completed two-factor enrollment");
        return user;
    }

    private User resolveTicketUser(String mfaTicket) {
        JwtClaims claims = jwtService.parse(mfaTicket, TokenType.MFA);
        if (claims.sessionId() == null || claims.sessionId().isBlank()) {
            throw new InvalidTokenException("Legacy MFA ticket has no authenticated session id");
        }
        User user = userRepository.findById(claims.userId())
                .orElseThrow(() -> new ResourceNotFoundException("Token subject no longer exists"));
        if (!user.isActive()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "ACCOUNT_DISABLED", "This account is disabled.");
        }
        if (user.getRole() != claims.role() || user.getSessionVersion() != claims.sessionVersion()) {
            throw new InvalidTokenException("MFA ticket is no longer valid");
        }
        return user;
    }

    private String secretFor(User user) {
        String ciphertext = user.getMfaSecretCiphertext();
        if (ciphertext != null && !ciphertext.isBlank()) {
            String secret = crypto.decrypt(ciphertext);
            user.setRuntimeMfaSecret(secret);
            return secret;
        }
        String legacy = user.getMfaSecret();
        if (legacy == null || legacy.isBlank()) {
            return null;
        }
        storeEncrypted(user, legacy);
        userRepository.save(user);
        return legacy;
    }

    private void storeEncryptedIfLegacy(User user, String secret) {
        if (user.getMfaSecretCiphertext() == null || user.getMfaSecretCiphertext().isBlank()) {
            storeEncrypted(user, secret);
            userRepository.save(user);
        }
    }

    private void storeEncrypted(User user, String secret) {
        user.setMfaSecretCiphertext(crypto.encrypt(secret));
        user.setRuntimeMfaSecret(secret);
        user.clearLegacyMfaSecret();
    }

    private void audit(String action, User user, String summary) {
        if (auditService != null) {
            auditService.record(null, "MFA", action, AuditActions.ENTITY_USER,
                    String.valueOf(user.getId()), summary);
        }
    }

    private static ApiException invalidCode() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_MFA_CODE",
                "That code is incorrect or expired. Try the current code from your authenticator app.");
    }
}
