package com.shifa.oms.auth;

import com.shifa.oms.auth.dto.ChangePasswordRequest;
import com.shifa.oms.auth.dto.LoginRequest;
import com.shifa.oms.auth.dto.MfaVerifyRequest;
import com.shifa.oms.auth.dto.RefreshRequest;
import com.shifa.oms.auth.dto.RegisterRequest;
import com.shifa.oms.auth.dto.TokenResponse;
import com.shifa.oms.common.ApiException;
import com.shifa.oms.common.DuplicateResourceException;
import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.mail.MailService;
import com.shifa.oms.mail.template.EmailModels;
import com.shifa.oms.mail.template.EmailRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/** Authenticates users and issues JWT pairs bound to sliding server sessions. */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final MailService mailService;
    private final EmailRenderer emailRenderer;
    private final MfaService mfaService;
    private final AuthSessionService authSessionService;

    @Autowired
    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       MailService mailService,
                       EmailRenderer emailRenderer,
                       MfaService mfaService,
                       AuthSessionService authSessionService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.mailService = mailService;
        this.emailRenderer = emailRenderer;
        this.mfaService = mfaService;
        this.authSessionService = authSessionService;
    }

    /** Compatibility constructor for direct unit tests; production uses the session-aware constructor. */
    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService,
                       MailService mailService, EmailRenderer emailRenderer, MfaService mfaService) {
        this(userRepository, passwordEncoder, jwtService, mailService, emailRenderer, mfaService, null);
    }

    @Transactional
    public TokenResponse register(RegisterRequest request) {
        String email = normalize(request.email());
        String mobile = request.mobile().trim();
        String username = (email != null ? email : mobile);
        if (userRepository.existsByUsername(username)) {
            throw new DuplicateResourceException("USERNAME_TAKEN",
                    "An account already exists for '" + username + "'.");
        }
        User user = new User(username, passwordEncoder.encode(request.password()), Role.CUSTOMER,
                request.fullName().trim(), email, mobile, true);
        User saved = userRepository.save(user);
        sendWelcomeEmail(saved);
        return tokensFor(saved, newSession(saved));
    }

    private void sendWelcomeEmail(User user) {
        String to = user.getEmail();
        if (to == null || to.isBlank()) return;
        try {
            mailService.send(emailRenderer.renderWelcome(new EmailModels.Welcome(user.getFullName())).toMessage(to.trim()));
        } catch (RuntimeException e) {
            log.warn("Welcome email to {} failed (registration still succeeded): {}", to, e.getMessage());
        }
    }

    private static String normalize(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    @Transactional
    public LoginOutcome login(LoginRequest request) {
        User user = userRepository.findByUsername(request.username())
                .filter(candidate -> passwordEncoder.matches(request.password(), candidate.getPasswordHash()))
                .orElseThrow(AuthService::invalidCredentials);
        if (!user.isActive()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "ACCOUNT_DISABLED", "This account is disabled.");
        }
        rejectExpiredForcedPassword(user);
        if (mfaService.mfaRequired(user)) {
            return LoginOutcome.challenge(mfaService.challengeFor(user));
        }
        return LoginOutcome.completed(tokensFor(user, newSession(user)));
    }

    @Transactional
    public TokenResponse verifyMfa(MfaVerifyRequest request) {
        JwtClaims ticket = jwtService.parse(request.mfaTicket(), TokenType.MFA);
        requireSessionId(ticket);
        User user = mfaService.verifyLogin(request.mfaTicket(), request.code());
        AuthSessionService.SessionHandle handle = touchOrReject(ticket, user);
        return tokensFor(user, handle);
    }

    @Transactional
    public TokenResponse confirmMfaEnrollment(MfaVerifyRequest request) {
        JwtClaims ticket = jwtService.parse(request.mfaTicket(), TokenType.MFA);
        requireSessionId(ticket);
        User user = mfaService.confirmEnrollment(request.mfaTicket(), request.code());
        // Enrollment increments session_version, so the pending password session
        // is intentionally replaced with a new row carrying the new version.
        return tokensFor(user, newSession(user));
    }

    @Transactional
    public TokenResponse refresh(RefreshRequest request) {
        JwtClaims claims = jwtService.parse(request.refreshToken(), TokenType.REFRESH);
        requireSessionId(claims);
        User user = userRepository.findById(claims.userId())
                .orElseThrow(() -> new InvalidTokenException("Token subject no longer exists"));
        if (!user.isActive()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "ACCOUNT_DISABLED", "This account is disabled.");
        }
        if (user.getRole() != claims.role() || user.getSessionVersion() != claims.sessionVersion()) {
            throw new InvalidTokenException("Refresh token is no longer valid");
        }
        rejectExpiredForcedPassword(user);
        return tokensFor(user, touchOrReject(claims, user));
    }

    @Transactional
    public TokenResponse changeMyPassword(Long userId, ChangePasswordRequest request) {
        return changeMyPassword(userId, request, (String) null);
    }

    /** Source-compatible bridge for callers that do not yet carry the session sid. */
    @Transactional
    public TokenResponse changeMyPassword(Long userId, ChangePasswordRequest request, Instant ignoredSessionStart) {
        return changeMyPassword(userId, request, (String) null);
    }

    /** Changes password while preserving the current server-side idle session when possible. */
    @Transactional
    public TokenResponse changeMyPassword(Long userId, ChangePasswordRequest request, String sessionId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User " + userId + " does not exist."));
        String newPassword = request.newPassword();
        if (user.isMustChangePassword() && user.getPasswordResetExpiresAt() != null
                && !user.getPasswordResetExpiresAt().isAfter(LocalDateTime.now())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "PASSWORD_RESET_EXPIRED",
                    "This temporary password has expired. Ask an administrator to reset it again.");
        }
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new ValidationException("Choose a new password different from your current one.");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(false);
        user.setPasswordResetExpiresAt(null);
        user.incrementSessionVersion();
        userRepository.save(user);

        AuthSessionService.SessionHandle handle = null;
        if (sessionId != null && authSessionService != null) {
            handle = authSessionService.rebind(sessionId, user.getId(), user.getSessionVersion())
                    .orElseThrow(() -> new InvalidTokenException("Authenticated session is no longer active"));
        }
        if (handle == null) handle = newSession(user);
        return tokensFor(user, handle);
    }

    private AuthSessionService.SessionHandle newSession(User user) {
        if (authSessionService != null) return authSessionService.create(user);
        String sid = UUID.randomUUID().toString();
        Instant idleExpiry = jwtService.sessionExpiresAt(jwtService.now());
        return new AuthSessionService.SessionHandle(sid, idleExpiry);
    }

    private AuthSessionService.SessionHandle touchOrReject(JwtClaims claims, User user) {
        if (authSessionService == null) {
            return new AuthSessionService.SessionHandle(claims.sessionId(),
                    jwtService.sessionExpiresAt(jwtService.now()));
        }
        return authSessionService.touch(claims.sessionId(), user.getId(), user.getSessionVersion())
                .orElseThrow(() -> new InvalidTokenException("Authenticated session has expired or been revoked"));
    }

    private static void requireSessionId(JwtClaims claims) {
        if (claims.sessionId() == null || claims.sessionId().isBlank()) {
            throw new InvalidTokenException("Legacy token has no authenticated session id");
        }
    }

    private TokenResponse tokensFor(User user, AuthSessionService.SessionHandle handle) {
        return new TokenResponse(
                jwtService.issueAccessToken(user, handle.sessionId(), handle.idleExpiresAt()),
                jwtService.issueRefreshToken(user, handle.sessionId(), handle.idleExpiresAt()),
                "Bearer", jwtService.accessTokenTtlSeconds(), user.getRole().name(), user.getUsername(),
                user.isMustChangePassword(), handle.idleExpiresAtEpochSeconds());
    }

    private void rejectExpiredForcedPassword(User user) {
        if (user.isMustChangePassword() && user.getPasswordResetExpiresAt() != null
                && !user.getPasswordResetExpiresAt().isAfter(LocalDateTime.now())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "PASSWORD_RESET_EXPIRED",
                    "This temporary password has expired. Ask an administrator to reset it again.");
        }
    }

    private static ApiException invalidCredentials() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid username or password.");
    }
}
