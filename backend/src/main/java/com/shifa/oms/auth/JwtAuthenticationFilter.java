package com.shifa.oms.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/** Validates bearer identity and atomically touches its server-side idle session. */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** Response header carrying the server idle expiry as epoch milliseconds; hint only. */
    public static final String SESSION_IDLE_EXPIRES_HEADER = "X-Session-Idle-Expires-At";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String SSE_TOKEN_PARAM = "access_token";

    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final AuthSessionService authSessionService;

    @Autowired
    public JwtAuthenticationFilter(JwtService jwtService, UserRepository userRepository,
                                   AuthSessionService authSessionService) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.authSessionService = authSessionService;
    }

    /** Compatibility constructor for direct filter tests without persistence. */
    public JwtAuthenticationFilter(JwtService jwtService, UserRepository userRepository) {
        this(jwtService, userRepository, null);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = extractBearerToken(request);
        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                JwtClaims claims = jwtService.parse(token, TokenType.ACCESS);
                if (claims.sessionId() == null || claims.sessionId().isBlank()) {
                    throw new InvalidTokenException("Legacy access token has no authenticated session id");
                }
                User user = userRepository.findById(claims.userId())
                        .orElseThrow(() -> new InvalidTokenException("Token subject no longer exists"));
                if (!user.isActive()
                        || user.getSessionVersion() != claims.sessionVersion()
                        || user.getRole() != claims.role()) {
                    throw new InvalidTokenException("Token is no longer valid");
                }
                if (user.isMustChangePassword() && !passwordChangeAllowed(request)) {
                    throw new InvalidTokenException("Password change is required");
                }
                AuthSessionService.SessionHandle touched = authSessionService == null
                        ? new AuthSessionService.SessionHandle(claims.sessionId(),
                        claims.sessionExpiresAt() == null ? java.time.Instant.now()
                                : claims.sessionExpiresAt())
                        : authSessionService.touch(claims.sessionId(), user.getId(), user.getSessionVersion())
                        .orElseThrow(() -> new InvalidTokenException("Authenticated session has expired or been revoked"));
                response.setHeader(SESSION_IDLE_EXPIRES_HEADER,
                        Long.toString(touched.idleExpiresAtEpochMillis()));
                AuthPrincipal principal = new AuthPrincipal(user.getId(), user.getUsername(), user.getRole(),
                        claims.sessionStartedAt(), claims.sessionId());
                var authorities = List.of(new SimpleGrantedAuthority(principal.role().authority()));
                var authentication = new UsernamePasswordAuthenticationToken(principal, null, authorities);
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (InvalidTokenException ex) {
                SecurityContextHolder.clearContext();
            }
        }
        filterChain.doFilter(request, response);
    }

    private static boolean passwordChangeAllowed(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return "/api/me/password".equals(uri) || "/api/auth/refresh".equals(uri);
    }

    private static String extractBearerToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String value = header.substring(BEARER_PREFIX.length()).trim();
            if (!value.isEmpty()) return value;
        }
        if (isSseRequest(request)) {
            String queryToken = request.getParameter(SSE_TOKEN_PARAM);
            if (queryToken != null && !queryToken.isBlank()) return queryToken.trim();
        }
        return null;
    }

    private static boolean isSseRequest(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri != null && uri.contains("/api/admin/events");
    }
}
