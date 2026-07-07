package com.shifa.oms.auth;

import com.shifa.oms.auth.dto.LoginRequest;
import com.shifa.oms.auth.dto.RefreshRequest;
import com.shifa.oms.auth.dto.RegisterRequest;
import com.shifa.oms.auth.dto.TokenResponse;
import com.shifa.oms.common.ApiException;
import com.shifa.oms.common.DuplicateResourceException;
import com.shifa.oms.mail.MailService;
import com.shifa.oms.mail.template.EmailModels;
import com.shifa.oms.mail.template.EmailRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authenticates users and mints JWT token pairs.
 *
 * <p>Login verifies the username/password against the stored BCrypt hash and,
 * on success, issues an access + refresh token (Req 5.1, 5.2). Refresh validates
 * a refresh token and re-issues a fresh pair, re-reading the user so a disabled
 * or role-changed account cannot keep operating on a stale token.
 *
 * <p>Failed logins return a single generic 401 regardless of whether the
 * username or the password was wrong, so the endpoint does not reveal which
 * usernames exist.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final MailService mailService;
    private final EmailRenderer emailRenderer;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       MailService mailService,
                       EmailRenderer emailRenderer) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.mailService = mailService;
        this.emailRenderer = emailRenderer;
    }

    /**
     * Registers a new storefront customer (role {@code CUSTOMER}) and returns a
     * fresh token pair so the client can auto-login. The username is the supplied
     * email when present, otherwise the 10-digit mobile. A duplicate username
     * yields a 409 {@link DuplicateResourceException}. The password is stored as a
     * BCrypt hash; the account is active immediately.
     */
    @Transactional
    public TokenResponse register(RegisterRequest request) {
        String email = normalize(request.email());
        String mobile = request.mobile().trim();
        String username = (email != null ? email : mobile);

        if (userRepository.existsByUsername(username)) {
            throw new DuplicateResourceException("USERNAME_TAKEN",
                    "An account already exists for '" + username + "'.");
        }

        User user = new User(
                username,
                passwordEncoder.encode(request.password()),
                Role.CUSTOMER,
                request.fullName().trim(),
                email,
                mobile,
                true);
        User saved = userRepository.save(user);
        sendWelcomeEmail(saved);
        return tokensFor(saved);
    }

    /**
     * Best-effort welcome email on registration. Only sent when the account has
     * a non-blank email; any failure is logged and swallowed so a mail outage can
     * never break signup.
     */
    private void sendWelcomeEmail(User user) {
        String to = user.getEmail();
        if (to == null || to.isBlank()) {
            return;
        }
        try {
            mailService.send(emailRenderer.renderWelcome(
                    new EmailModels.Welcome(user.getFullName())).toMessage(to.trim()));
        } catch (RuntimeException e) {
            log.warn("Welcome email to {} failed (registration still succeeded): {}", to, e.getMessage());
        }
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    @Transactional(readOnly = true)
    public TokenResponse login(LoginRequest request) {
        User user = userRepository.findByUsername(request.username())
                .filter(candidate -> passwordEncoder.matches(request.password(), candidate.getPasswordHash()))
                .orElseThrow(AuthService::invalidCredentials);
        if (!user.isActive()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "ACCOUNT_DISABLED", "This account is disabled.");
        }
        return tokensFor(user);
    }

    @Transactional(readOnly = true)
    public TokenResponse refresh(RefreshRequest request) {
        JwtClaims claims = jwtService.parse(request.refreshToken(), TokenType.REFRESH);
        User user = userRepository.findById(claims.userId())
                .orElseThrow(() -> new InvalidTokenException("Token subject no longer exists"));
        if (!user.isActive()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "ACCOUNT_DISABLED", "This account is disabled.");
        }
        return tokensFor(user);
    }

    private TokenResponse tokensFor(User user) {
        return new TokenResponse(
                jwtService.issueAccessToken(user),
                jwtService.issueRefreshToken(user),
                "Bearer",
                jwtService.accessTokenTtlSeconds(),
                user.getRole().name(),
                user.getUsername());
    }

    private static ApiException invalidCredentials() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid username or password.");
    }
}
