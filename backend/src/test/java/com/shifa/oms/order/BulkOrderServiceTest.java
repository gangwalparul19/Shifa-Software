package com.shifa.oms.order;

import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.Role;
import com.shifa.oms.label.LabelService;
import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.order.dto.BulkActionResult;
import com.shifa.oms.packing.PackingService;
import com.shifa.oms.platform.outbox.OutboxEvent;
import com.shifa.oms.platform.outbox.OutboxEventPublisher;
import com.shifa.oms.platform.outbox.OutboxEventRepository;
import com.shifa.oms.platform.storage.StorageService;
import com.shifa.oms.statemachine.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

/**
 * Example-based unit tests for {@link BulkOrderService} covering the Wave 2
 * bulk-order partial-success semantics (ROADMAP 2.2):
 * <ul>
 *   <li>bulk-approve: some approved, some skipped with a reason;</li>
 *   <li>empty ids yields empty result;</li>
 *   <li>an unknown id is skipped (not found);</li>
 *   <li>bulk-mark-packed only packs {@code Label_Generated} orders, skipping the rest.</li>
 * </ul>
 *
 * <p>Per the environment constraint (concrete classes are not mockable on this
 * JVM), the collaborators {@link AdminOrderService} and {@link PackingService}
 * are constructed as REAL instances over mocked interface repositories plus a
 * hand-written in-memory {@link StorageService}; only the repositories are
 * Mockito mocks.
 */
@ExtendWith(MockitoExtension.class)
class BulkOrderServiceTest {

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private OutboxEventRepository outboxEventRepository;

    private BulkOrderService service;

    private final AuthPrincipal admin = new AuthPrincipal(1L, "admin", Role.ADMIN);

    @BeforeEach
    void setUp() {
        LabelService labelService = new LabelService(orderRepository, inMemoryStorage());
        // Real central workflow service; audit is best-effort against a mock repo
        // (no Mockito mock of a concrete class — Java 25).
        com.shifa.oms.audit.AuditService auditService = new com.shifa.oms.audit.AuditService(
                org.mockito.Mockito.mock(com.shifa.oms.audit.AuditEventRepository.class),
                new com.shifa.oms.auth.CurrentUserService());
        OrderWorkflowService workflowService = new OrderWorkflowService(auditService);
        AdminOrderService adminOrderService =
                new AdminOrderService(orderRepository, labelService, workflowService,
                        new OutboxEventPublisher(outboxEventRepository));
        PackingService packingService = new PackingService(
                orderRepository, new OutboxEventPublisher(outboxEventRepository), workflowService,
                org.mockito.Mockito.mock(com.shifa.oms.auth.UserRepository.class));
        service = new BulkOrderService(adminOrderService, packingService, orderRepository);

        lenient().when(orderRepository.save(any(OrderEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(outboxEventRepository.save(any(OutboxEvent.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private static StorageService inMemoryStorage() {
        return new StorageService() {
            @Override
            public StoredObjectRef store(String prefix, String originalFilename,
                                         String contentType, byte[] content) {
                return new StoredObjectRef(prefix + "/" + originalFilename);
            }

            @Override
            public Optional<StoredObject> load(String key) {
                return Optional.empty();
            }
        };
    }

    private OrderEntity orderIn(long id, OrderStatus status) {
        OrderEntity order = new OrderEntity(
                "SHR-" + String.format("%06d", id), OrderSource.STOREFRONT, null,
                "Asha", "9812345678", "12 MG Road", "Pune", "Maharashtra", "411001");
        order.applyAmounts(new BigDecimal("240.00"), BigDecimal.ZERO,
                new BigDecimal("240.00"), new BigDecimal("240.00"), PaymentStatus.COD);
        order.setOrderStatus(status);
        ReflectionTestUtils.setField(order, "id", id);
        return order;
    }

    // --- bulk-approve partial success ---------------------------------------

    @Test
    void bulkApproveApprovesEligibleAndSkipsIneligibleWithReason() {
        OrderEntity pending = orderIn(1L, OrderStatus.PENDING_ADMIN_APPROVAL);
        OrderEntity alreadyApproved = orderIn(2L, OrderStatus.APPROVED);
        lenient().when(orderRepository.findById(1L)).thenReturn(Optional.of(pending));
        lenient().when(orderRepository.findById(2L)).thenReturn(Optional.of(alreadyApproved));
        lenient().when(orderRepository.findById(3L)).thenReturn(Optional.empty());

        BulkActionResult result = service.bulkApprove(List.of(1L, 2L, 3L), admin);

        assertThat(result.succeeded()).containsExactly(1L);
        assertThat(result.skipped()).hasSize(2);
        assertThat(result.skipped().get(0).id()).isEqualTo(2L);
        assertThat(result.skipped().get(0).reason()).contains("not awaiting approval");
        assertThat(result.skipped().get(1).id()).isEqualTo(3L);
        assertThat(result.skipped().get(1).reason()).contains("not found");
        // The approved order advanced through the state machine to Label_Generated.
        assertThat(pending.getOrderStatus()).isEqualTo(OrderStatus.LABEL_GENERATED);
        // The ineligible order was left untouched.
        assertThat(alreadyApproved.getOrderStatus()).isEqualTo(OrderStatus.APPROVED);
    }

    @Test
    void bulkApproveWithEmptyIdsReturnsEmptyResult() {
        BulkActionResult result = service.bulkApprove(List.of(), admin);

        assertThat(result.succeeded()).isEmpty();
        assertThat(result.skipped()).isEmpty();
    }

    @Test
    void bulkApproveUnknownIdIsSkipped() {
        lenient().when(orderRepository.findById(99L)).thenReturn(Optional.empty());

        BulkActionResult result = service.bulkApprove(List.of(99L), admin);

        assertThat(result.succeeded()).isEmpty();
        assertThat(result.skipped()).singleElement()
                .satisfies(s -> {
                    assertThat(s.id()).isEqualTo(99L);
                    assertThat(s.reason()).contains("not found");
                });
    }

    // --- bulk-mark-packed ---------------------------------------------------

    @Test
    void bulkMarkPackedPacksLabelGeneratedAndSkipsOthers() {
        OrderEntity ready = orderIn(1L, OrderStatus.LABEL_GENERATED);
        OrderEntity wrongState = orderIn(2L, OrderStatus.PACKED);
        lenient().when(orderRepository.findById(1L)).thenReturn(Optional.of(ready));
        lenient().when(orderRepository.findById(2L)).thenReturn(Optional.of(wrongState));
        lenient().when(orderRepository.findByOrderCode(ready.getOrderCode()))
                .thenReturn(Optional.of(ready));

        BulkActionResult result = service.bulkMarkPacked(List.of(1L, 2L), admin);

        assertThat(result.succeeded()).containsExactly(1L);
        assertThat(result.skipped()).singleElement()
                .satisfies(s -> {
                    assertThat(s.id()).isEqualTo(2L);
                    assertThat(s.reason()).contains("not ready to pack");
                });
        assertThat(ready.getOrderStatus()).isEqualTo(OrderStatus.PACKED);
    }
}
