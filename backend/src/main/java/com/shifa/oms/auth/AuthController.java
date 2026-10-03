package com.shifa.oms.auth;

import com.shifa.oms.auth.dto.LoginRequest;
import com.shifa.oms.auth.dto.RefreshRequest;
import com.shifa.oms.auth.dto.RegisterRequest;
import com.shifa.oms.auth.dto.TokenResponse;
import com.shifa.oms.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
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
 *   <li>{@code POST /api/auth/login} — exchange credentials for a token pair.</li>
 *   <li>{@code POST /api/auth/refresh} — exchange a refresh token for a new pair.</li>
 * </ul>
 *
 * All three are permitted without authentication in the security filter chain
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

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        String ip = clientIp(httpRequest);
        // Reject up front if this username|IP is locked out (throws 429).
        loginRateLimiter.checkNotLocked(request.username(), ip);
        try {
            TokenResponse response = authService.login(request);
            loginRateLimiter.recordSuccess(request.username(), ip);
            return response;
        } catch (ApiException ex) {
            // Count credential/account failures toward the lockout; re-throw as-is
            // so the caller still sees the original 401.
            loginRateLimiter.recordFailure(request.username(), ip);
            throw ex;
        }
    }

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
