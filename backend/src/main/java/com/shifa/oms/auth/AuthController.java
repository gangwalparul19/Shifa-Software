package com.shifa.oms.auth;

import com.shifa.oms.auth.dto.LoginRequest;
import com.shifa.oms.auth.dto.MfaVerifyRequest;
import com.shifa.oms.auth.dto.RefreshRequest;
import com.shifa.oms.auth.dto.RegisterRequest;
import com.shifa.oms.auth.dto.TokenResponse;
import com.shifa.oms.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public authentication endpoints (Req 5.2).
 *
 * <ul>
 *   <li>{@code POST /api/auth/register} — self-register a storefront customer,
 *       returning a token pair for immediate auto-login (201).</li>
 *   <li>{@code POST /api/auth/login} — exchange credentials for a token pair, OR
 *       (when MFA is active for the user) a 200 MFA challenge the client must
 *       complete before tokens are issued.</li>
 *   <li>{@code POST /api/auth/mfa/verify} — complete the TOTP second factor for
 *       an enrolled user and receive the token pair.</li>
 *   <li>{@code POST /api/auth/mfa/enroll/confirm} — confirm the first TOTP code
 *       to finish enrollment and receive the token pair.</li>
 *   <li>{@code POST /api/auth/refresh} — exchange a refresh token for a new pair.</li>
 * </ul>
 *
 * All of these are permitted without authentication in the security filter chain
 * (the {@code /api/auth/**} public matcher).
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final LoginRateLimiter loginRateLimiter;

    public AuthController(AuthService authService, LoginRateLimiter loginRateLimiter) {
        this.authService = authService;
        this.loginRateLimiter = loginRateLimiter;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public TokenResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    /**
     * Password step. On success returns either the token pair (no MFA) or a 200
     * MFA challenge ({@code MFA_REQUIRED}/{@code MFA_ENROLL}) — never a 401 — so
     * the client's refresh interceptor does not try to silently refresh it.
     * Reaching a correct password clears the brute-force counter even when a
     * second factor is still owed (the password itself was right).
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        String ip = clientIp(httpRequest);
        // Reject up front if this username|IP is locked out (throws 429).
        loginRateLimiter.checkNotLocked(request.username(), ip);
        try {
            LoginOutcome outcome = authService.login(request);
            loginRateLimiter.recordSuccess(request.username(), ip);
            return outcome.requiresMfa()
                    ? ResponseEntity.ok(outcome.challenge())
                    : ResponseEntity.ok(outcome.tokens());
        } catch (ApiException ex) {
            // Count credential/account failures toward the lockout; re-throw as-is
            // so the caller still sees the original 401.
            loginRateLimiter.recordFailure(request.username(), ip);
            throw ex;
        }
    }

    /**
     * MFA step for an already-enrolled user: validate the current authenticator
     * code and issue the token pair. The 6-digit code is brute-forceable, so the
     * same per-{@code username|IP} throttle guards it; the ticket's username is
     * resolved from the ticket, so we key the throttle on the client IP.
     */
    @PostMapping("/mfa/verify")
    public TokenResponse verifyMfa(@Valid @RequestBody MfaVerifyRequest request, HttpServletRequest httpRequest) {
        String ip = clientIp(httpRequest);
        loginRateLimiter.checkNotLocked(MFA_RATE_KEY, ip);
        try {
            TokenResponse response = authService.verifyMfa(request);
            loginRateLimiter.recordSuccess(MFA_RATE_KEY, ip);
            return response;
        } catch (ApiException ex) {
            loginRateLimiter.recordFailure(MFA_RATE_KEY, ip);
            throw ex;
        }
    }

    /**
     * Enrollment confirmation: validate the first authenticator code, finish
     * enrollment, and issue the token pair. Throttled like the verify step.
     */
    @PostMapping("/mfa/enroll/confirm")
    public TokenResponse confirmMfaEnrollment(@Valid @RequestBody MfaVerifyRequest request,
                                              HttpServletRequest httpRequest) {
        String ip = clientIp(httpRequest);
        loginRateLimiter.checkNotLocked(MFA_RATE_KEY, ip);
        try {
            TokenResponse response = authService.confirmMfaEnrollment(request);
            loginRateLimiter.recordSuccess(MFA_RATE_KEY, ip);
            return response;
        } catch (ApiException ex) {
            loginRateLimiter.recordFailure(MFA_RATE_KEY, ip);
            throw ex;
        }
    }

    /**
     * Fixed pseudo-username for throttling the MFA code steps per client IP
     * (the real username is inside the signed ticket, not the request body).
     */
    private static final String MFA_RATE_KEY = "__mfa__";

    /**
     * Best-effort client IP. Behind Nginx the real client is the first entry of
     * {@code X-Forwarded-For}; fall back to the socket address otherwise.
     */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request);
    }
}
