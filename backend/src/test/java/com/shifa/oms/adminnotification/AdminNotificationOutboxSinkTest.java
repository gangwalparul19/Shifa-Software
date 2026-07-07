package com.shifa.oms.adminnotification;

import com.shifa.oms.platform.outbox.OutboxEvent;
import com.shifa.oms.platform.outbox.OutboxEventPublisher;
import com.shifa.oms.platform.outbox.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests that the notification-persistence hook is wired into the outbox publisher
 * without disturbing the SSE flow ("operations depth" Feature 2):
 * <ul>
 *   <li>publishing an admin-notification event (ORDER_PACKED) persists a row via
 *       {@link AdminNotificationService#record};</li>
 *   <li>publishing a non-admin integration event (COURIER_ASSIGN) persists no
 *       notification;</li>
 *   <li>a failure in the sink never propagates out of {@code publish} (best-effort);</li>
 *   <li>LOW_STOCK maps to warning severity and carries no order id.</li>
 * </ul>
 *
 * <p>Uses a hand-written fake service (not a Mockito mock) because
 * {@link AdminNotificationService} is a concrete class, which the runtime's
 * Mockito/Byte Buddy cannot subclass-mock.
 */
@ExtendWith(MockitoExtension.class)
class AdminNotificationOutboxSinkTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    /** A single captured {@code record(...)} invocation. */
    private record RecordCall(String type, String title, String detail, String severity,
                              Long orderId, String orderCode, Long sourceEventId) {
    }

    /** Hand-written fake capturing calls (and optionally throwing) so no concrete-class mock is needed. */
    private static final class RecordingNotificationService extends AdminNotificationService {
        private final List<RecordCall> calls = new ArrayList<>();
        private boolean throwOnRecord;

        RecordingNotificationService() {
            super(null);
        }

        @Override
        public AdminNotification record(String type, String title, String detail, String severity,
                                        Long orderId, String orderCode, Long sourceEventId) {
            calls.add(new RecordCall(type, title, detail, severity, orderId, orderCode, sourceEventId));
            if (throwOnRecord) {
                throw new RuntimeException("notif db down");
            }
            return null;
        }
    }

    private RecordingNotificationService notificationService;
    private OutboxEventPublisher publisher;

    @BeforeEach
    void setUp() {
        when(outboxEventRepository.save(any(OutboxEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        notificationService = new RecordingNotificationService();
        AdminNotificationOutboxSink sink = new AdminNotificationOutboxSink(notificationService);
        publisher = new OutboxEventPublisher(outboxEventRepository, List.of(sink));
    }

    @Test
    void adminEventPersistsNotification() {
        publisher.publishOrderPacked(42L, "SHR-42", "Asha", "packer1");

        assertThat(notificationService.calls).hasSize(1);
        RecordCall call = notificationService.calls.get(0);
        assertThat(call.type()).isEqualTo(OutboxEvent.EVENT_ORDER_PACKED);
        assertThat(call.severity()).isEqualTo(AdminNotification.SEVERITY_SUCCESS);
        assertThat(call.orderId()).isEqualTo(42L);
        assertThat(call.orderCode()).isEqualTo("SHR-42");
        assertThat(call.title()).contains("SHR-42");
    }

    @Test
    void integrationEventPersistsNoNotification() {
        publisher.publishCourierAssign(7L, "SHR-7");
        assertThat(notificationService.calls).isEmpty();
    }

    @Test
    void sinkFailureNeverBreaksPublish() {
        notificationService.throwOnRecord = true;

        // Must not propagate — the originating operation cannot be broken by the sink.
        OutboxEvent event = publisher.publishLowStock(3L, "SKU3", "Tulsi", 1, 5, false);

        verify(outboxEventRepository).save(any(OutboxEvent.class));
        assertThat(event).isNotNull();
        assertThat(notificationService.calls).hasSize(1);
    }

    @Test
    void lowStockMapsToWarningSeverityAndProductPayload() {
        publisher.publishLowStock(3L, "SKU3", "Tulsi", 1, 5, false);

        assertThat(notificationService.calls).hasSize(1);
        RecordCall call = notificationService.calls.get(0);
        assertThat(call.type()).isEqualTo(OutboxEvent.EVENT_LOW_STOCK);
        assertThat(call.severity()).isEqualTo(AdminNotification.SEVERITY_WARNING);
        // Product-scoped: no order id / order code.
        assertThat(call.orderId()).isNull();
        assertThat(call.orderCode()).isNull();
        assertThat(call.title()).contains("Tulsi");
    }
}
