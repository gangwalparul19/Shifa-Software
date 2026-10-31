package com.shifa.oms.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthSessionServiceTest {

    private static final Instant NOW = Instant.parse("2024-06-01T12:00:00Z");
    private AuthSessionRepository repository;
    private AuthSessionService service;
    private User user;

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(AuthSessionRepository.class);
        JwtProperties properties = new JwtProperties();
        properties.setSessionTtl(Duration.ofMinutes(30));
        service = new AuthSessionService(repository, properties, Clock.fixed(NOW, ZoneOffset.UTC));
        user = new User("alice", "hash", Role.SALESPERSON, "Alice", true);
        setId(user, 42L);
        when(repository.save(any(AuthSession.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void createStoresOnlyHashAndReturnsRawSidForJwt() {
        AuthSessionService.SessionHandle handle = service.create(user);
        ArgumentCaptor<AuthSession> captor = ArgumentCaptor.forClass(AuthSession.class);
        verify(repository).save(captor.capture());

        assertThat(handle.sessionId()).isNotBlank();
        assertThat(captor.getValue().getSessionIdHash()).isEqualTo(AuthSessionService.hash(handle.sessionId()));
        assertThat(captor.getValue().getSessionIdHash()).doesNotContain(handle.sessionId());
        assertThat(captor.getValue().getLastActivityAt()).isEqualTo(LocalDateTime.of(2024, 6, 1, 12, 0));
        assertThat(handle.idleExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(30)));
    }

    @Test
    void touchUsesStrictIdleBoundaryAndReturnsCurrentExpiry() {
        String sid = "sid-123";
        when(repository.touchIfActive(AuthSessionService.hash(sid), 42L, 0L,
                LocalDateTime.of(2024, 6, 1, 12, 0),
                LocalDateTime.of(2024, 6, 1, 11, 30))).thenReturn(1);

        Optional<AuthSessionService.SessionHandle> touched = service.touch(sid, 42L, 0L);

        assertThat(touched).isPresent();
        assertThat(touched.orElseThrow().idleExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(30)));
    }

    @Test
    void atomicTouchFailureMeansIdleExpiredOrRevoked() {
        when(repository.touchIfActive(any(), any(), anyLong(), any(), any())).thenReturn(0);

        assertThat(service.touch("sid-123", 42L, 0L)).isEmpty();
    }

    @Test
    void rebindUpdatesVersionAfterPasswordChange() {
        when(repository.rebindIfActive(AuthSessionService.hash("sid-123"), 42L, 1L,
                LocalDateTime.of(2024, 6, 1, 12, 0),
                LocalDateTime.of(2024, 6, 1, 11, 30))).thenReturn(1);

        assertThat(service.rebind("sid-123", 42L, 1L)).isPresent();
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
}
