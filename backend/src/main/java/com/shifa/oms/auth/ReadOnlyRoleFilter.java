package com.shifa.oms.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shifa.oms.common.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.EnumSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Second line of defence for view-only roles (today: {@link Role#CRM_LEAD}).
 *
 * <p>A view-only role is kept read-only in two independent ways. The first is that it is
 * never named on a write endpoint's {@code @PreAuthorize}. This filter is the second: it
 * refuses any request that is not a plain read ({@code GET}, {@code HEAD}, {@code OPTIONS})
 * from such a user, before the request reaches a controller. So if a write endpoint were
 * ever opened to the role by mistake, it would still be rejected here.
 *
 * <p>Only a short list of requests that change nothing about the business is let through:
 * signing in and refreshing the session, the user changing their own password, marking
 * their own notifications read, and registering their own browser for push alerts.
 *
 * <p>This is not a replacement for {@code @PreAuthorize}, and it cannot make a
 * side-effecting {@code GET} safe. A read endpoint that also writes (for example one that
 * allocates a number the first time it is opened) must simply not be granted to the role.
 *
 * <p>Runs after {@link JwtAuthenticationFilter}, so the caller is already identified; it
 * does nothing for anonymous requests or for other roles. It writes the 403 itself because
 * a filter at this position runs before Spring's exception translation.
 */
public final class ReadOnlyRoleFilter extends OncePerRequestFilter {

    /** Roles that may only read. */
    static final Set<Role> READ_ONLY_ROLES = EnumSet.of(Role.CRM_LEAD);

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    /** Sign-in, MFA and session refresh (public endpoints that issue/renew a session). */
    private static final String AUTH_PREFIX = "/api/auth/";

    private static final Set<String> ALLOWED_POST_PATHS = Set.of(
            "/api/me/password",
            "/api/notifications/read-all",
            "/api/notifications/push/subscribe",
            "/api/notifications/push/unsubscribe");

    private static final Pattern NOTIFICATION_READ = Pattern.compile("^/api/notifications/\\d+/read$");

    private final ObjectMapper objectMapper;

    public ReadOnlyRoleFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (isReadOnlyCaller() && !isAllowed(request.getMethod(), pathOf(request))) {
            reject(request, response);
            return;
        }
        filterChain.doFilter(request, response);
    }

    /** Whether the signed-in caller holds a view-only role. */
    private static boolean isReadOnlyCaller() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null
                && authentication.getPrincipal() instanceof AuthPrincipal principal
                && READ_ONLY_ROLES.contains(principal.role());
    }

    /**
     * Whether a view-only user may make this request. Reads always pass; of the rest, only
     * the explicit list of self-service actions does.
     */
    static boolean isAllowed(String method, String path) {
        if (method == null || SAFE_METHODS.contains(method.toUpperCase())) {
            return true;
        }
        if (!"POST".equalsIgnoreCase(method)) {
            return false;
        }
        return path.startsWith(AUTH_PREFIX)
                || ALLOWED_POST_PATHS.contains(path)
                || NOTIFICATION_READ.matcher(path).matches();
    }

    /** The request path without the context path or a trailing slash. */
    private static String pathOf(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            return "";
        }
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && uri.startsWith(context)) {
            uri = uri.substring(context.length());
        }
        int end = uri.length();
        while (end > 1 && uri.charAt(end - 1) == '/') {
            end--;
        }
        return uri.substring(0, end);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ErrorResponse body = ErrorResponse.of(
                HttpStatus.FORBIDDEN.value(),
                HttpStatus.FORBIDDEN.getReasonPhrase(),
                "READ_ONLY_ROLE",
                "Your account has view-only access, so this action is not available.",
                request.getRequestURI());
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
