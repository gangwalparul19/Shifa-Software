package com.shifa.oms.adminnotification;

import com.shifa.oms.adminnotification.dto.AdminNotificationResponse;
import com.shifa.oms.common.PageResponse;
import com.shifa.oms.common.ResourceNotFoundException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AdminNotificationService} with a mocked repository:
 * <ul>
 *   <li>{@code record} persists a row with the given fields;</li>
 *   <li>{@code record} de-duplicates on the source outbox event id;</li>
 *   <li>{@code list} filters unreadOnly + type and paginates;</li>
 *   <li>{@code markRead} flips the flag and stamps {@code read_at};</li>
 *   <li>{@code markAllRead} performs the bulk update;</li>
 *   <li>{@code unreadCount} returns the repository count.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class AdminNotificationServiceTest {

    @Mock
    private AdminNotificationRepository repository;

    private AdminNotificationService service;

    @BeforeEach
    void setUp() {
        service = new AdminNotificationService(repository);
    }

    @Test
    void recordPersistsARow() {
        when(repository.save(any(AdminNotification.class))).thenAnswer(inv -> inv.getArgument(0));

        service.record("ORDER_PACKED", "Order packed SHR-1", "Customer: A",
                AdminNotification.SEVERITY_SUCCESS, 1L, "SHR-1", 99L);

        ArgumentCaptor<AdminNotification> captor = ArgumentCaptor.forClass(AdminNotification.class);
        verify(repository).save(captor.capture());
        AdminNotification saved = captor.getValue();
        assertThat(saved.getType()).isEqualTo("ORDER_PACKED");
        assertThat(saved.getTitle()).isEqualTo("Order packed SHR-1");
        assertThat(saved.getSeverity()).isEqualTo(AdminNotification.SEVERITY_SUCCESS);
        assertThat(saved.getOrderId()).isEqualTo(1L);
        assertThat(saved.getOrderCode()).isEqualTo("SHR-1");
        assertThat(saved.getSourceEventId()).isEqualTo(99L);
        assertThat(saved.isRead()).isFalse();
    }

    @Test
    void recordDeDuplicatesOnSourceEventId() {
        when(repository.existsBySourceEventId(99L)).thenReturn(true);

        AdminNotification result = service.record("ORDER_PACKED", "dup", null, null, 1L, "SHR-1", 99L);

        assertThat(result).isNull();
        verify(repository, never()).save(any(AdminNotification.class));
    }

    @Test
    void listFiltersUnreadOnlyAndPaginates() {
        Pageable pageable = PageRequest.of(0, 10);
        AdminNotification n = new AdminNotification("WHATSAPP_FAILED", "WhatsApp failed", "err",
                AdminNotification.SEVERITY_DANGER, 2L, "SHR-2", 5L);
        when(repository.search(eq(true), isNull(), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(n), pageable, 1));

        PageResponse<AdminNotificationResponse> page = service.list(true, null, pageable);

        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.content().get(0).type()).isEqualTo("WHATSAPP_FAILED");
        assertThat(page.content().get(0).severity()).isEqualTo(AdminNotification.SEVERITY_DANGER);
        verify(repository).search(eq(true), isNull(), eq(pageable));
    }

    @Test
    void markReadFlipsFlagAndStampsReadAt() {
        AdminNotification n = new AdminNotification("LOW_STOCK", "Low stock", null,
                AdminNotification.SEVERITY_WARNING, null, null, 7L);
        when(repository.findById(3L)).thenReturn(Optional.of(n));
        when(repository.save(any(AdminNotification.class))).thenAnswer(inv -> inv.getArgument(0));

        AdminNotificationResponse response = service.markRead(3L);

        assertThat(response.read()).isTrue();
        assertThat(response.readAt()).isNotNull();
        assertThat(n.isRead()).isTrue();
    }

    @Test
    void markReadUnknownIdIsRejected() {
        when(repository.findById(404L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.markRead(404L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void markAllReadPerformsBulkUpdate() {
        when(repository.markAllRead(any(LocalDateTime.class))).thenReturn(4);

        int flipped = service.markAllRead();

        assertThat(flipped).isEqualTo(4);
        verify(repository).markAllRead(any(LocalDateTime.class));
    }

    @Test
    void unreadCountReturnsRepositoryCount() {
        when(repository.countByReadFalse()).thenReturn(11L);
        assertThat(service.unreadCount()).isEqualTo(11L);
    }
}
