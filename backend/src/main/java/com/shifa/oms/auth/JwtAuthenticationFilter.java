package com.shifa.oms.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Extracts a bearer JWT from the {@code Authorization} header, validates it, and
 * populates the Spring Security context with the {@link AuthPrincipal} and its
 * {@code ROLE_<role>} authority for the duration of the request.
 *
 * <p>The filter is deliberately permissive about missing/invalid tokens: it does
 * not reject the request itself. If no valid authentication is established, the
 * request simply proceeds unauthenticated, and the security filter chain's entry
 * point returns 401 for protected resources (Req 5.2) while public endpoints
 * (catalog/search/PWA/auth) still work. This avoids a stale token blocking public
 * pages.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String SSE_TOKEN_PARAM = "access_token";

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = extractBearerToken(request);
        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                JwtClaims claims = jwtService.parse(token, TokenType.ACCESS);
                AuthPrincipal principal = claims.toPrincipal();
                var authorities = List.of(new SimpleGrantedAuthority(principal.role().authority()));
                var authentication =
                        new UsernamePasswordAuthenticationToken(principal, null, authorities);
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (InvalidTokenException ex) {
                // Invalid/expired token: proceed unauthenticated. Protected routes
                // will be rejected with 401 by the entry point.
                SecurityContextHolder.clearContext();
            }
        }
        filterChain.doFilter(request, response);
    }

    private static String extractBearerToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String value = header.substring(BEARER_PREFIX.length()).trim();
            if (!value.isEmpty()) {
                return value;
            }
        }
        // The browser EventSource API cannot set an Authorization header, so the
        // admin SSE stream (/api/admin/events) passes the access token as an
        // ?access_token= query parameter instead. It is validated identically to
        // a bearer token (signature/expiry/type), and the endpoint still enforces
        // the ADMIN authority via @PreAuthorize.
        if (isSseRequest(request)) {
            String queryToken = request.getParameter(SSE_TOKEN_PARAM);
            if (queryToken != null && !queryToken.isBlank()) {
                return queryToken.trim();
            }
        }
        return null;
    }

    private static boolean isSseRequest(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri != null && uri.contains("/api/admin/events");
    }
}
