package com.shifa.oms.ledger.autopost;

import com.shifa.oms.adminnotification.AdminNotification;
import com.shifa.oms.adminnotification.StaffNotificationDispatcher;
import com.shifa.oms.auth.Role;
import com.shifa.oms.ledger.VoucherService;
import com.shifa.oms.ledger.VoucherRepository;
import com.shifa.oms.ledger.domain.DraftVoucher;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.platform.outbox.OutboxEvent;
import com.shifa.oms.platform.outbox.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Integration-style test for the <strong>non-destructive auto-posting failure</strong> path of
 * {@link LedgerPostingDrainer} (General Ledger, Reqs 8.5, 9.4, 17.4).
 *
 * <p>Mirrors the sibling {@code WhatsAppOutboxDrainer}/{@code EmailOutboxDrainer} integration tests:
 * the real drainer is wired over a mocked {@link OutboxEventRepository} interface plus real / recording
 * collaborators, so no database or Spring context is required. The scenario proves the design's
 * central promise — a posting failure only marks the outbox row {@code FAILED} and raises an ADMIN
 * notification; it <em>never</em> touches the source aggregate.
 *
 * <p><strong>How the failure is forced.</strong> A {@code PENDING} {@code LEDGER_POST} outbox row
 * points at an {@code ORDER} source id whose order does not exist. {@link LedgerAutoPostingService} is
 * built real over a mocked {@link OrderRepository} whose {@code findById} returns empty, so
 * {@code buildDraft} throws {@code ResourceNotFoundException} before any voucher is derived. With
 * {@code max-attempts = 1} the first failure exhausts retries immediately, so the drainer marks the
 * row {@code FAILED} and dispatches the ADMIN notification in a single cycle.
 *
 * <p>Asserts:
 * <ul>
 *   <li>(a) the outbox row ends {@code FAILED} with an error recorded (Reqs 8.5, 9.4);</li>
 *   <li>(b) exactly one ADMIN {@code LEDGER_POST_FAILED} (DANGER) notification is raised (Req 8.5);</li>
 *   <li>(c) the source-aggregate repository saw only reads and <em>no</em> write, and the ledger
 *       {@code VoucherService.postForSource} was never invoked — proving non-destructiveness
 *       (Req 17.4).</li>
 * </ul>
 */
class LedgerPostingDrainerFailureTest {

    private OutboxEventRepository outboxEventRepository;
    private List<OutboxEvent> savedEvents;

    private OrderRepository orderRepository;
    private LedgerAutoPostingService ledgerAutoPostingService;
    private RecordingVoucherService voucherService;
    private SourcePostingGuard sourcePostingGuard;
    private RecordingNotificationDispatcher notificationDispatcher;

    private LedgerPostingDrainer drainer;

    @BeforeEach
    void setUp() {
        outboxEventRepository = mock(OutboxEventRepository.class);
        savedEvents = new ArrayList<>();
        when(outboxEventRepository.save(any(OutboxEvent.class))).thenAnswer(i -> {
            OutboxEvent e = i.getArgument(0);
            if (!savedEvents.contains(e)) {
                savedEvents.add(e);
            }
            return e;
        });

        // (a) LedgerAutoPostingService, real, over a mocked OrderRepository whose findById returns
        // empty so buildDraft throws ResourceNotFoundException (the source order does not exist).
        orderRepository = mock(OrderRepository.class);
        when(orderRepository.findById(anyLong())).thenReturn(Optional.empty());
        ledgerAutoPostingService = new LedgerAutoPostingService(
                orderRepository, null, null, null, null, null);

        // SourcePostingGuard, real, over mocked repository interfaces: nothing posted yet.
        SourcePostingLogRepository logRepository = mock(SourcePostingLogRepository.class);
        VoucherRepository voucherRepository = mock(VoucherRepository.class);
        when(logRepository.existsBySourceTypeAndSourceId(anyString(), anyLong())).thenReturn(false);
        when(voucherRepository.findBySourceTypeAndSourceId(anyString(), anyLong()))
                .thenReturn(Optional.empty());
        sourcePostingGuard = new SourcePostingGuard(logRepository, voucherRepository);

        // Recording collaborators (Java 25: concrete classes cannot be Mockito-mocked).
        voucherService = new RecordingVoucherService();
        notificationDispatcher = new RecordingNotificationDispatcher();

        drainer = new LedgerPostingDrainer(
                outboxEventRepository, ledgerAutoPostingService, voucherService,
                sourcePostingGuard, notificationDispatcher);
        // max-attempts = 1 so the first failure exhausts retries and the row is marked FAILED at once.
        ReflectionTestUtils.setField(drainer, "maxAttempts", 1);
        ReflectionTestUtils.setField(drainer, "retryBackoffMs", 60000L);
    }

    @Test
    void aPostingFailureMarksTheOutboxRowFailedAndNotifiesAdminWithoutTouchingTheSource() {
        long sourceId = 4242L;
        OutboxEvent event = new OutboxEvent(
                OutboxEvent.AGGREGATE_LEDGER_SOURCE, sourceId, OutboxEvent.EVENT_LEDGER_POST,
                Map.of("sourceType", SourceType.ORDER.name(), "sourceId", sourceId));
        when(outboxEventRepository.findDue(any(), any(), any())).thenReturn(List.of(event));

        int processed = drainer.drainPostings();

        assertThat(processed).isEqualTo(1);

        // (a) The outbox row ends FAILED with an error recorded (Reqs 8.5, 9.4).
        assertThat(event.getStatus()).isEqualTo(OutboxEvent.STATUS_FAILED);
        assertThat(event.getLastError()).isNotBlank();
        assertThat(savedEvents).contains(event);

        // (b) Exactly one ADMIN LEDGER_POST_FAILED (DANGER) notification was raised (Req 8.5).
        assertThat(notificationDispatcher.roleNotifications).hasSize(1);
        RoleNotification raised = notificationDispatcher.roleNotifications.get(0);
        assertThat(raised.type()).isEqualTo("LEDGER_POST_FAILED");
        assertThat(raised.severity()).isEqualTo(AdminNotification.SEVERITY_DANGER);
        assertThat(raised.role()).isEqualTo(Role.ADMIN);
        assertThat(raised.detail()).contains("unchanged");

        // (c) Non-destructiveness (Req 17.4): the source aggregate was only READ (findById), never
        // written, and no voucher was posted for the failed source.
        verify(orderRepository).findById(sourceId);
        verify(orderRepository, never()).save(any());
        assertThat(voucherService.postForSourceCalls.get())
                .as("no voucher posted when derivation fails")
                .isZero();
    }

    // --- Recording collaborators -----------------------------------------------------------------

    /**
     * A recording {@link VoucherService} whose {@code postForSource} counts invocations (expected zero
     * on the failure path — {@code buildDraft} throws first). Constructed with null collaborators since
     * the overridden method never touches them.
     */
    private static final class RecordingVoucherService extends VoucherService {
        final AtomicInteger postForSourceCalls = new AtomicInteger();

        RecordingVoucherService() {
            super(null, null, null, null, null, null, null, null);
        }

        @Override
        public com.shifa.oms.ledger.Voucher postForSource(DraftVoucher draft, String sourceType, Long sourceId) {
            postForSourceCalls.incrementAndGet();
            throw new AssertionError("postForSource must not be called when buildDraft fails");
        }
    }

    /** One captured role-addressed notification. */
    private record RoleNotification(String type, String title, String detail, String severity, Role role) {
    }

    /**
     * A recording {@link StaffNotificationDispatcher} that captures {@code dispatchToRole} calls
     * instead of persisting them. Constructed with null collaborators since the overridden method
     * never touches them.
     */
    private static final class RecordingNotificationDispatcher extends StaffNotificationDispatcher {
        final List<RoleNotification> roleNotifications = new ArrayList<>();

        RecordingNotificationDispatcher() {
            super(null, null);
        }

        @Override
        public AdminNotification dispatchToRole(String type, String title, String detail,
                                                String severity, Long orderId, String orderCode,
                                                Long sourceEventId, Role role) {
            roleNotifications.add(new RoleNotification(type, title, detail, severity, role));
            return null;
        }
    }
}
