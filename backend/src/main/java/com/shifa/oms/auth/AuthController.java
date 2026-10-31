package com.shifa.oms.auth;

import com.shifa.oms.auth.dto.LoginRequest;
import com.shifa.oms.auth.dto.MfaVerifyRequest;
import com.shifa.oms.auth.dto.RefreshRequest;
import com.shifa.oms.auth.dto.RegisterRequest;
import com.shifa.oms.auth.dto.TokenResponse;
import com.shifa.oms.common.ApiException;
import com.shifa.oms.common.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final ClientIpResolver clientIpResolver;
    private final MfaRateLimitKeyResolver mfaRateLimitKeyResolver;

    @Autowired
    public AuthController(AuthService authService,
                          LoginRateLimiter loginRateLimiter,
                          ClientIpResolver clientIpResolver,
                          MfaRateLimitKeyResolver mfaRateLimitKeyResolver) {
        this.authService = authService;
        this.loginRateLimiter = loginRateLimiter;
        this.clientIpResolver = clientIpResolver;
        this.mfaRateLimitKeyResolver = mfaRateLimitKeyResolver;
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
        String ip = clientIpResolver.resolve(httpRequest);
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
     * MFA step for an already-enrolled user. The throttle uses the signed ticket
     * user plus the resolved client IP, with one bounded fallback bucket for an
     * invalid ticket; the MFA service remains authoritative for ticket errors.
     */
    @PostMapping("/mfa/verify")
    public TokenResponse verifyMfa(@Valid @RequestBody MfaVerifyRequest request, HttpServletRequest httpRequest) {
        String ip = clientIpResolver.resolve(httpRequest);
        String mfaKey = mfaRateLimitKeyResolver.resolve(request.mfaTicket());
        loginRateLimiter.checkNotLocked(mfaKey, ip);
        try {
            TokenResponse response = authService.verifyMfa(request);
            loginRateLimiter.recordSuccess(mfaKey, ip);
            return response;
        } catch (ApiException ex) {
            loginRateLimiter.recordFailure(mfaKey, ip);
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
        String ip = clientIpResolver.resolve(httpRequest);
        String mfaKey = mfaRateLimitKeyResolver.resolve(request.mfaTicket());
        loginRateLimiter.checkNotLocked(mfaKey, ip);
        try {
            TokenResponse response = authService.confirmMfaEnrollment(request);
            loginRateLimiter.recordSuccess(mfaKey, ip);
            return response;
        } catch (ApiException ex) {
            loginRateLimiter.recordFailure(mfaKey, ip);
            throw ex;
        }
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request);
    }
}
