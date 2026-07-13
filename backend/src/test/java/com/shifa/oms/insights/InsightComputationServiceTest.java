package com.shifa.oms.insights;

import com.shifa.oms.adminnotification.AdminNotification;
import com.shifa.oms.adminnotification.AdminNotificationRepository;
import com.shifa.oms.adminnotification.StaffNotificationDispatcher;
import com.shifa.oms.audit.AuditEventRepository;
import com.shifa.oms.audit.AuditService;
import com.shifa.oms.auth.CurrentUserService;
import com.shifa.oms.auth.Role;
import com.shifa.oms.courier.CourierCompanyRepository;
import com.shifa.oms.courier.CourierRecordRepository;
import com.shifa.oms.insights.domain.InsightSeverity;
import com.shifa.oms.insights.domain.InsightThresholds;
import com.shifa.oms.insights.domain.InsightType;
import com.shifa.oms.inventory.StockMovementRepository;
import com.shifa.oms.lead.LeadRepository;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.platform.outbox.OutboxEvent;
import com.shifa.oms.platform.outbox.OutboxEventPublisher;
import com.shifa.oms.platform.outbox.OutboxEventRepository;
import com.shifa.oms.product.ProductRepository;
import com.shifa.oms.reconciliation.ReceivableEntity;
import com.shifa.oms.reconciliation.ReceivableRepository;
import com.shifa.oms.reconciliation.domain.ReceivableType;
import com.shifa.oms.returns.OrderReturnRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Idempotency + notification test for {@link InsightComputationService}
 * (task 3.4, design §Testing; Req 1.2, 10.1). Uses a fixed {@link Clock} and a
 * small seeded dataset in which the only insight produced is a WARNING
 * COD-outstanding build-up (unsettled COD above the threshold), keeping the
 * assertions focused.
 *
 * <p>Repository <em>interfaces</em> are stubbed via Mockito (allowed under the
 * Java 25 runtime gotcha — only concrete-class mocking is disallowed); the
 * concrete collaborators ({@link OutboxEventPublisher},
 * {@link StaffNotificationDispatcher}, {@link AuditService}) are real instances
 * wired over stubbed repositories, and the {@link InsightRepository} is backed by
 * an in-memory store so a same-date rerun is observably a replace, not an insert.
 *
 * <p>Asserts: (a) a same-date rerun replaces the date's insights — the persisted
 * count stays 1, never duplicating rows (Req 1.2); and (b) the WARNING insight
 * raises exactly one ADMIN notification, and the rerun does not raise a second
 * (notification de-dup via the pre-existing natural-key capture, Req 10.1).
 */
class InsightComputationServiceTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2025-03-15T10:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate TODAY = LocalDate.of(2025, 3, 15);

    private final List<InsightEntity> insightStore = new ArrayList<>();
    private final List<AdminNotification> notificationStore = new ArrayList<>();
    private final AtomicLong insightSeq = new AtomicLong(0);

    private InsightRepository insightRepository;
    private InsightComputationService service;

    @BeforeEach
    void setUp() {
        insightRepository = mock(InsightRepository.class);
        wireInsightStore();

        OrderRepository orderRepository = mock(OrderRepository.class);
        when(orderRepository.findByCreatedAtBetween(any(), any())).thenReturn(List.of());
        when(orderRepository.findAll()).thenReturn(List.of());
        when(orderRepository.findByOrderStatusInOrderByCreatedAtDesc(any())).thenReturn(List.of());

        StockMovementRepository stockMovementRepository = mock(StockMovementRepository.class);
        when(stockMovementRepository.findByMovementTypeAndCreatedAtBetween(any(), any(), any()))
                .thenReturn(List.of());

        ProductRepository productRepository = mock(ProductRepository.class);
        CourierRecordRepository courierRecordRepository = mock(CourierRecordRepository.class);
        when(courierRecordRepository.findAll()).thenReturn(List.of());
        CourierCompanyRepository courierCompanyRepository = mock(CourierCompanyRepository.class);

        OrderReturnRepository orderReturnRepository = mock(OrderReturnRepository.class);
        when(orderReturnRepository.search(any(), any(), any(), any(), any()))
                .thenReturn(org.springframework.data.domain.Page.empty());

        // The only trigger: unsettled COD (60000) above the 50000 threshold → one WARNING insight.
        ReceivableRepository receivableRepository = mock(ReceivableRepository.class);
        when(receivableRepository.findByTypeAndSettledFalseOrderByCreatedAtDescIdDesc(
                ReceivableType.COD_RECEIVABLE))
                .thenReturn(List.of(new ReceivableEntity(1L, 1L, ReceivableType.COD_RECEIVABLE,
                        new BigDecimal("60000.00"))));

        LeadRepository leadRepository = mock(LeadRepository.class);
        when(leadRepository.findAll()).thenReturn(List.of());

        // Real outbox publisher over a stubbed repo (echoes the saved event, no side sinks).
        OutboxEventRepository outboxEventRepository = mock(OutboxEventRepository.class);
        when(outboxEventRepository.save(any(OutboxEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        OutboxEventPublisher outboxEventPublisher = new OutboxEventPublisher(outboxEventRepository);

        // Real dispatcher over an in-memory notifications store.
        AdminNotificationRepository notificationRepository = mock(AdminNotificationRepository.class);
        when(notificationRepository.save(any(AdminNotification.class))).thenAnswer(inv -> {
            AdminNotification n = inv.getArgument(0);
            notificationStore.add(n);
            return n;
        });
        StaffNotificationDispatcher dispatcher = new StaffNotificationDispatcher(notificationRepository,
                new com.shifa.oms.push.WebPushService(
                        null, new com.fasterxml.jackson.databind.ObjectMapper(), "", "", ""));

        // Real audit service over a stubbed repo (never-throwing, best-effort).
        AuditEventRepository auditEventRepository = mock(AuditEventRepository.class);
        AuditService auditService = new AuditService(auditEventRepository, new CurrentUserService());

        service = new InsightComputationService(
                insightRepository, orderRepository, stockMovementRepository, productRepository,
                courierRecordRepository, courierCompanyRepository, orderReturnRepository,
                receivableRepository, leadRepository, outboxEventPublisher, dispatcher, auditService,
                InsightThresholds.defaults(), 7, FIXED_CLOCK);
    }

    @Test
    void firstRunPersistsAndNotifiesTheWarningInsight() {
        int count = service.computeForToday();

        assertThat(count).isEqualTo(1);
        assertThat(insightStore).hasSize(1);
        InsightEntity persisted = insightStore.get(0);
        assertThat(persisted.getInsightType()).isEqualTo(InsightType.COD_OUTSTANDING_BUILDUP);
        assertThat(persisted.getSeverity()).isEqualTo(InsightSeverity.WARNING);
        assertThat(persisted.getComputedDate()).isEqualTo(TODAY);

        // Exactly one admin notification, addressed to ADMIN, WARNING severity.
        assertThat(notificationStore).hasSize(1);
        AdminNotification notification = notificationStore.get(0);
        assertThat(notification.getRecipientRole()).isEqualTo(Role.ADMIN);
        assertThat(notification.getSeverity()).isEqualTo(AdminNotification.SEVERITY_WARNING);
    }

    @Test
    void sameDateRerunReplacesInsightsAndDoesNotReNotify() {
        int first = service.computeForToday();
        int second = service.computeForToday();

        assertThat(first).isEqualTo(1);
        assertThat(second).isEqualTo(1);
        // Replaced, not duplicated: still exactly one row for the date.
        assertThat(insightStore).hasSize(1);
        assertThat(insightStore.get(0).getComputedDate()).isEqualTo(TODAY);
        // The rerun did NOT raise a second notification (de-dup on the natural key).
        assertThat(notificationStore).hasSize(1);
    }

    /** Backs the {@link #insightRepository} mock with an in-memory store so delete+saveAll is observable. */
    private void wireInsightStore() {
        when(insightRepository.findMaxComputedDate())
                .thenAnswer(inv -> insightStore.stream()
                        .map(InsightEntity::getComputedDate)
                        .max(LocalDate::compareTo));

        when(insightRepository.findByComputedDateOrderBySeverityAscIdDesc(any()))
                .thenAnswer(inv -> {
                    LocalDate d = inv.getArgument(0);
                    return insightStore.stream()
                            .filter(e -> d.equals(e.getComputedDate()))
                            .toList();
                });

        when(insightRepository.findByComputedDateAndDismissedFalse(any()))
                .thenAnswer(inv -> {
                    LocalDate d = inv.getArgument(0);
                    return insightStore.stream()
                            .filter(e -> d.equals(e.getComputedDate()) && !e.isDismissed())
                            .toList();
                });

        doAnswer(inv -> {
            LocalDate d = inv.getArgument(0);
            insightStore.removeIf(e -> d.equals(e.getComputedDate()));
            return null;
        }).when(insightRepository).deleteByComputedDate(any());

        when(insightRepository.saveAll(any())).thenAnswer(inv -> {
            Iterable<InsightEntity> entities = inv.getArgument(0);
            List<InsightEntity> saved = new ArrayList<>();
            for (InsightEntity e : entities) {
                assignId(e, insightSeq.incrementAndGet());
                insightStore.add(e);
                saved.add(e);
            }
            return saved;
        });

        when(insightRepository.save(any(InsightEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(insightRepository.findById(anyLong())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            return insightStore.stream().filter(e -> id.equals(idOf(e))).findFirst();
        });
    }

    /** Assigns the generated id onto a persisted entity (id has no setter). */
    private static void assignId(InsightEntity e, long id) {
        try {
            java.lang.reflect.Field field = InsightEntity.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(e, id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static Long idOf(InsightEntity e) {
        return e.getId();
    }
}
