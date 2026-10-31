package com.shifa.oms.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockFilterChain;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class JwtAuthenticationFilterTest {
    private static final Instant NOW = Instant.parse("2024-06-01T12:00:00Z");

    private UserRepository users;
    private JwtService jwt;
    private User user;
    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        users = Mockito.mock(UserRepository.class);
        JwtProperties properties = new JwtProperties();
        properties.setSecret("test-secret-that-is-long-enough-for-hmac-sha256-signing");
        properties.setAccessTokenTtl(java.time.Duration.ofHours(1));
        jwt = new JwtService(properties, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
        user = new User("alice", "hash", Role.SALESPERSON, "Alice", true);
        setId(user, 42L);
        filter = new JwtAuthenticationFilter(jwt, users);
    }

    @AfterEach
    void clear() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Test
    void revokedVersionAndRoleCannotAuthenticateAnOldAccessToken() throws Exception {
        when(users.findById(42L)).thenReturn(Optional.of(user));
        String token = jwt.issueAccessToken(user);

        request(token, "/api/orders");
        assertThat(org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication())
                .isNotNull();

        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        user.incrementSessionVersion();
        request(token, "/api/orders");
        assertThat(org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication())
                .isNull();
    }

    @Test
    void forcedPasswordUserCanOnlyReachPasswordChangeEndpoint() throws Exception {
        user.setMustChangePassword(true);
        when(users.findById(42L)).thenReturn(Optional.of(user));
        String token = jwt.issueAccessToken(user);

        request(token, "/api/orders");
        assertThat(org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication())
                .isNull();

        request(token, "/api/me/password");
        assertThat(org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication())
                .isNotNull();
    }

    private void request(String token, String uri) throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.addHeader("Authorization", "Bearer " + token);
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
    }

    private static void setId(User user, long id) {
        try {
            var field = User.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(user, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void accessTokenIsAcceptedAtTheFormerAbsoluteBoundaryWhenItsJwtHasNotExpired() throws Exception {
        when(users.findById(42L)).thenReturn(Optional.of(user));
        String token = jwt.issueAccessToken(user);

        JwtProperties properties = new JwtProperties();
        properties.setSecret("test-secret-that-is-long-enough-for-hmac-sha256-signing");
        properties.setAccessTokenTtl(java.time.Duration.ofHours(1));
        JwtService atDeadline = new JwtService(properties, new ObjectMapper(),
                Clock.fixed(NOW.plusSeconds(30 * 60L), ZoneOffset.UTC));
        filter = new JwtAuthenticationFilter(atDeadline, users);

        request(token, "/api/orders");
        assertThat(org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication())
                .isNotNull();
    }
    @Test
    void bearerRequestTouchesSessionAndExposesIdleExpiryHeader() throws Exception {
        AuthSessionRepository sessionRepository = Mockito.mock(AuthSessionRepository.class);
        String token = jwt.issueAccessToken(user);
        String sid = jwt.parse(token, TokenType.ACCESS).sessionId();
        when(users.findById(42L)).thenReturn(Optional.of(user));
        when(sessionRepository.touchIfActive(Mockito.eq(AuthSessionService.hash(sid)), Mockito.eq(42L),
                Mockito.eq(user.getSessionVersion()), Mockito.any(), Mockito.any())).thenReturn(1);
        filter = new JwtAuthenticationFilter(jwt, users, sessionService(sessionRepository));

        MockHttpServletResponse response = requestWithResponse(token, "/api/orders");

        assertThat(response.getHeader(JwtAuthenticationFilter.SESSION_IDLE_EXPIRES_HEADER))
                .isEqualTo(String.valueOf(NOW.plusSeconds(1800).toEpochMilli()));
        Mockito.verify(sessionRepository).touchIfActive(Mockito.eq(AuthSessionService.hash(sid)), Mockito.eq(42L),
                Mockito.eq(user.getSessionVersion()), Mockito.any(), Mockito.any());
    }

    @Test
    void bearerRequestIsRejectedWhenAtomicIdleTouchFails() throws Exception {
        AuthSessionRepository sessionRepository = Mockito.mock(AuthSessionRepository.class);
        String token = jwt.issueAccessToken(user);
        String sid = jwt.parse(token, TokenType.ACCESS).sessionId();
        when(users.findById(42L)).thenReturn(Optional.of(user));
        when(sessionRepository.touchIfActive(Mockito.eq(AuthSessionService.hash(sid)), Mockito.eq(42L),
                Mockito.eq(user.getSessionVersion()), Mockito.any(), Mockito.any())).thenReturn(0);
        filter = new JwtAuthenticationFilter(jwt, users, sessionService(sessionRepository));

        request(token, "/api/orders");

        assertThat(org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication())
                .isNull();
    }

    private AuthSessionService sessionService(AuthSessionRepository repository) {
        JwtProperties properties = new JwtProperties();
        properties.setSessionTtl(java.time.Duration.ofMinutes(30));
        return new AuthSessionService(repository, properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private MockHttpServletResponse requestWithResponse(String token, String uri)
            throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }
}
