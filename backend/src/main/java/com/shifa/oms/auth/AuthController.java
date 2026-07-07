package com.shifa.oms.auth;

import com.shifa.oms.auth.dto.LoginRequest;
import com.shifa.oms.auth.dto.RefreshRequest;
import com.shifa.oms.auth.dto.RegisterRequest;
import com.shifa.oms.auth.dto.TokenResponse;
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

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public TokenResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request);
    }
}
