package com.shifa.oms.audit;

import com.shifa.oms.audit.dto.AuditEventResponse;
import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.CurrentUserService;
import com.shifa.oms.auth.Role;
import com.shifa.oms.common.PageResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AuditService} with a mocked repository and a hand-written
 * fake {@link CurrentUserService} (concrete class subclass, not a Mockito mock):
 * <ul>
 *   <li>{@code record} persists with the resolved actor + all fields;</li>
 *   <li>{@code record} is null-safe and records a null actor when unauthenticated;</li>
 *   <li>{@code record} never throws even if the repository blows up;</li>
 *   <li>{@code list} passes the filters through and paginates newest-first.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

    @Mock
    private AuditEventRepository repository;

    /** Hand-written fake so we control the "current user" without mocking a concrete class. */
    private static final class FakeCurrentUserService extends CurrentUserService {
        private AuthPrincipal principal;

        @Override
        public Optional<AuthPrincipal> currentUser() {
            return Optional.ofNullable(principal);
        }
    }

    private FakeCurrentUserService currentUserService;
    private AuditService service;

    @BeforeEach
    void setUp() {
        currentUserService = new FakeCurrentUserService();
        service = new AuditService(repository, currentUserService);
    }

    @Test
    void recordPersistsWithActorAndFields() {
        currentUserService.principal = new AuthPrincipal(7L, "asha", Role.ADMIN);
        when(repository.save(any(AuditEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        service.record(AuditActions.ORDER_APPROVED, AuditActions.ENTITY_ORDER, "42", "Approved order SHR-42");

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(repository).save(captor.capture());
        AuditEvent saved = captor.getValue();
        assertThat(saved.getActorUserId()).isEqualTo(7L);
        assertThat(saved.getActorUsername()).isEqualTo("asha");
        assertThat(saved.getAction()).isEqualTo(AuditActions.ORDER_APPROVED);
        assertThat(saved.getEntityType()).isEqualTo(AuditActions.ENTITY_ORDER);
        assertThat(saved.getEntityId()).isEqualTo("42");
        assertThat(saved.getSummary()).isEqualTo("Approved order SHR-42");
    }

    @Test
    void recordIsNullSafeWhenUnauthenticated() {
        currentUserService.principal = null;
        when(repository.save(any(AuditEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        service.record(AuditActions.SETTINGS_UPDATED, AuditActions.ENTITY_SETTINGS, null, "Updated settings");

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getActorUserId()).isNull();
        assertThat(captor.getValue().getActorUsername()).isNull();
        assertThat(captor.getValue().getEntityId()).isNull();
    }

    @Test
    void recordNeverThrowsWhenRepositoryFails() {
        currentUserService.principal = new AuthPrincipal(1L, "root", Role.ADMIN);
        when(repository.save(any(AuditEvent.class))).thenThrow(new RuntimeException("db down"));

        // Must not propagate — auditing is best-effort and cannot break the business op.
        AuditEvent result = service.record(AuditActions.USER_CREATED, AuditActions.ENTITY_USER, "9", "x");
        assertThat(result).isNull();
    }

    @Test
    void listPassesFiltersThroughAndPaginates() {
        Pageable pageable = PageRequest.of(0, 20);
        AuditEvent e = new AuditEvent(3L, "raj", AuditActions.ORDER_REJECTED,
                AuditActions.ENTITY_ORDER, "5", "Rejected order SHR-5: fraud");
        when(repository.search(eq(AuditActions.ORDER_REJECTED), isNull(), isNull(),
                any(LocalDateTime.class), any(LocalDateTime.class), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(e), pageable, 1));

        PageResponse<AuditEventResponse> page = service.list(
                AuditActions.ORDER_REJECTED, "  ", null,
                LocalDateTime.now().minusDays(1), LocalDateTime.now(), pageable);

        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.content()).hasSize(1);
        assertThat(page.content().get(0).action()).isEqualTo(AuditActions.ORDER_REJECTED);
        assertThat(page.content().get(0).actorUsername()).isEqualTo("raj");
        // Blank entityType filter is normalised to null (no filter).
        verify(repository).search(eq(AuditActions.ORDER_REJECTED), isNull(), isNull(),
                any(LocalDateTime.class), any(LocalDateTime.class), eq(pageable));
        verify(repository, never()).findAll();
    }
}
