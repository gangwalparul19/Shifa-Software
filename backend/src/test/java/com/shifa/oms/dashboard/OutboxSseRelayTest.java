package com.shifa.oms.dashboard;

import com.shifa.oms.platform.outbox.OutboxEvent;
import com.shifa.oms.platform.outbox.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Focused tests for {@link OutboxSseRelay} (task 19.3): a scan/status change
 * that persisted an admin-notification outbox row results in the corresponding
 * SSE event being relayed and the row marked delivered, while nothing is lost
 * when no admin is connected (Req 11.2, 13.3).
 *
 * <p>The outbox repository is a Mockito mock (an interface, mockable here); the
 * SSE broker is a hand-written {@link FakeBroker} subclass rather than a Mockito
 * mock, since concrete classes cannot be inline-mocked on this JVM (the same
 * approach the packing tests use for the outbox publisher). This keeps the test
 * focused on the relay logic without a database or a live HTTP connection.
 */
@ExtendWith(MockitoExtension.class)
class OutboxSseRelayTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    private FakeBroker broker;
    private OutboxSseRelay relay;

    @BeforeEach
    void setUp() {
        broker = new FakeBroker();
        // metricsService is only used by pushLiveStats(), not exercised here.
        relay = new OutboxSseRelay(outboxEventRepository, broker, null);
        lenient().when(outboxEventRepository.save(any(OutboxEvent.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private OutboxEvent packedEvent() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", 42L);
        payload.put("orderCode", "SHR-000042");
        payload.put("customerName", "Asha");
        return new OutboxEvent(OutboxEvent.AGGREGATE_ORDER, 42L,
                OutboxEvent.EVENT_ORDER_PACKED, payload);
    }

    // --- Relay picks up a PENDING ORDER_PACKED row and marks it delivered (Req 11.2) ---

    @Test
    @SuppressWarnings("unchecked")
    void relaysPendingPackedNotificationAndMarksItSent() {
        OutboxEvent packed = packedEvent();
        broker.active = true;
        when(outboxEventRepository.findByStatusOrderByCreatedAtAsc(OutboxEvent.STATUS_PENDING))
                .thenReturn(List.of(packed));

        int relayed = relay.relayPending();

        assertThat(relayed).isEqualTo(1);
        // Broadcast as an ORDER_PACKED SSE event carrying the persisted payload.
        assertThat(broker.broadcasts).containsExactly(OutboxEvent.EVENT_ORDER_PACKED);
        assertThat(broker.lastData).isInstanceOf(Map.class);
        Map<String, Object> data = (Map<String, Object>) broker.lastData;
        assertThat(data).containsEntry("orderCode", "SHR-000042");
        // Row marked SENT and persisted so it is not re-sent.
        assertThat(packed.getStatus()).isEqualTo(OutboxEvent.STATUS_SENT);
        verify(outboxEventRepository).save(packed);
    }

    // --- Nothing lost when no admin is connected (Req 11.2 "persist when none connected") ---

    @Test
    void doesNotRelayOrMarkSentWhenNoAdminConnected() {
        broker.active = false;

        int relayed = relay.relayPending();

        assertThat(relayed).isZero();
        // The row stays PENDING; the relay never even queries or broadcasts.
        verify(outboxEventRepository, never())
                .findByStatusOrderByCreatedAtAsc(OutboxEvent.STATUS_PENDING);
        assertThat(broker.broadcasts).isEmpty();
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
    }

    // --- Integration event types are left for their own drainers ---

    @Test
    void ignoresNonAdminNotificationEventTypes() {
        OutboxEvent courierAssign = new OutboxEvent(OutboxEvent.AGGREGATE_ORDER, 7L,
                OutboxEvent.EVENT_COURIER_ASSIGN, Map.of("orderId", 7L));
        OutboxEvent whatsappNotify = new OutboxEvent(OutboxEvent.AGGREGATE_ORDER, 7L,
                OutboxEvent.EVENT_WHATSAPP_NOTIFY, Map.of("orderId", 7L));
        broker.active = true;
        when(outboxEventRepository.findByStatusOrderByCreatedAtAsc(OutboxEvent.STATUS_PENDING))
                .thenReturn(List.of(courierAssign, whatsappNotify));

        int relayed = relay.relayPending();

        assertThat(relayed).isZero();
        assertThat(broker.broadcasts).isEmpty();
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
        // The integration rows remain PENDING for the courier/WhatsApp drainers.
        assertThat(courierAssign.getStatus()).isEqualTo(OutboxEvent.STATUS_PENDING);
        assertThat(whatsappNotify.getStatus()).isEqualTo(OutboxEvent.STATUS_PENDING);
    }

    // --- Relaying all five admin-notification types ---

    @Test
    void relaysEachAdminNotificationType() {
        OutboxEvent statusChanged = new OutboxEvent(OutboxEvent.AGGREGATE_ORDER, 1L,
                OutboxEvent.EVENT_ORDER_STATUS_CHANGED, Map.of("newStatus", "DISPATCHED"));
        OutboxEvent claim = new OutboxEvent(OutboxEvent.AGGREGATE_ORDER, 2L,
                OutboxEvent.EVENT_CLAIM_FILED_REQUIRED, Map.of("awb", "AWB1"));
        OutboxEvent courierFailed = new OutboxEvent(OutboxEvent.AGGREGATE_ORDER, 3L,
                OutboxEvent.EVENT_COURIER_ASSIGN_FAILED, Map.of("error", "timeout"));
        OutboxEvent whatsappFailed = new OutboxEvent(OutboxEvent.AGGREGATE_ORDER, 4L,
                OutboxEvent.EVENT_WHATSAPP_FAILED, Map.of("error", "rejected"));
        broker.active = true;
        when(outboxEventRepository.findByStatusOrderByCreatedAtAsc(OutboxEvent.STATUS_PENDING))
                .thenReturn(List.of(statusChanged, claim, courierFailed, whatsappFailed));

        int relayed = relay.relayPending();

        assertThat(relayed).isEqualTo(4);
        assertThat(broker.broadcasts).containsExactlyInAnyOrder(
                OutboxEvent.EVENT_ORDER_STATUS_CHANGED,
                OutboxEvent.EVENT_CLAIM_FILED_REQUIRED,
                OutboxEvent.EVENT_COURIER_ASSIGN_FAILED,
                OutboxEvent.EVENT_WHATSAPP_FAILED);
        assertThat(statusChanged.getStatus()).isEqualTo(OutboxEvent.STATUS_SENT);
        assertThat(claim.getStatus()).isEqualTo(OutboxEvent.STATUS_SENT);
        assertThat(courierFailed.getStatus()).isEqualTo(OutboxEvent.STATUS_SENT);
        assertThat(whatsappFailed.getStatus()).isEqualTo(OutboxEvent.STATUS_SENT);
    }

    /** A hand-written broker fake recording broadcasts and toggling liveness. */
    private static final class FakeBroker extends AdminSseBroker {
        private boolean active;
        private final List<String> broadcasts = new ArrayList<>();
        private Object lastData;

        @Override
        public boolean hasActiveEmitters() {
            return active;
        }

        @Override
        public void broadcast(String eventName, Object data) {
            broadcasts.add(eventName);
            lastData = data;
        }
    }
}
