package com.shifa.oms.order;

import com.shifa.oms.audit.AuditEventRepository;
import com.shifa.oms.audit.AuditService;
import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.CurrentUserService;
import com.shifa.oms.auth.Role;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.label.LabelService;
import com.shifa.oms.order.dto.ApprovalQueueItemResponse;
import com.shifa.oms.order.dto.OrderResponse;
import com.shifa.oms.platform.storage.StorageService;
import com.shifa.oms.statemachine.IllegalStatusTransitionException;
import com.shifa.oms.statemachine.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Example-based unit tests for {@link AdminOrderService} covering the admin
 * order-approval edge cases (Req 9.1, 9.3, 9.4, 8.3):
 * <ul>
 *   <li>reject without a reason is rejected (400) and does not change status;</li>
 *   <li>approve/reject only apply legal transitions — approving a non-pending
 *       order is a 409 via the state machine and the status is retained;</li>
 *   <li>the approval queue returns only {@code Pending_Admin_Approval} orders.</li>
 * </ul>
 * The repository is mocked so these run without a database.
 */
@ExtendWith(MockitoExtension.class)
class AdminOrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    private AdminOrderService service;

    private final AuthPrincipal admin = new AuthPrincipal(1L, "admin", Role.ADMIN);

    @BeforeEach
    void setUp() {
        // Real LabelService with an in-memory storage backend so approval can
        // auto-generate the internal label (Req 10.1-10.3) without touching disk.
        LabelService labelService = new LabelService(orderRepository, inMemoryStorage());
        // Real central workflow service; audit is best-effort against a mock repo
        // (no Mockito mock of a concrete class — Java 25).
        AuditService auditService = new AuditService(
                mock(AuditEventRepository.class), new CurrentUserService());
        OrderWorkflowService workflowService = new OrderWorkflowService(auditService);
        service = new AdminOrderService(orderRepository, labelService, workflowService);
        lenient().when(orderRepository.save(any(OrderEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    /** A minimal in-memory {@link StorageService} that just returns a key. */
    private static StorageService inMemoryStorage() {
        return new StorageService() {
            @Override
            public StoredObjectRef store(String prefix, String originalFilename,
                                         String contentType, byte[] content) {
                return new StoredObjectRef(prefix + "/" + originalFilename);
            }

            @Override
            public java.util.Optional<StoredObject> load(String key) {
                return java.util.Optional.empty();
            }
        };
    }

    private OrderEntity orderIn(OrderStatus status) {
        OrderEntity order = new OrderEntity(
                "SHR-000123", OrderSource.STOREFRONT, null,
                "Asha", "9812345678", "12 MG Road", "Pune", "Maharashtra", "411001");
        order.applyAmounts(new BigDecimal("240.00"), BigDecimal.ZERO,
                new BigDecimal("240.00"), new BigDecimal("240.00"),
                com.shifa.oms.order.domain.PaymentStatus.COD);
        order.setOrderStatus(status);
        return order;
    }

    // --- Reject without reason (Req 9.4) ------------------------------------

    @Test
    void rejectWithoutReasonIsRejectedAndStatusUnchanged() {
        OrderEntity order = orderIn(OrderStatus.PENDING_ADMIN_APPROVAL);
        lenient().when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.reject(1L, "  ", admin))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("rejection reason is required");

        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PENDING_ADMIN_APPROVAL);
        assertThat(order.getRejectionReason()).isNull();
        assertThat(order.getStatusHistory()).isEmpty();
        verify(orderRepository, never()).save(any(OrderEntity.class));
    }

    @Test
    void rejectWithNullReasonIsRejected() {
        assertThatThrownBy(() -> service.reject(1L, null, admin))
                .isInstanceOf(ValidationException.class);
        verify(orderRepository, never()).save(any(OrderEntity.class));
    }

    // --- Approve legal transition (Req 9.3) ---------------------------------

    @Test
    void approvePendingOrderApprovesAndAutoGeneratesLabel() {
        OrderEntity order = orderIn(OrderStatus.PENDING_ADMIN_APPROVAL);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

        OrderResponse response = service.approve(1L, admin);

        // Approval immediately generates the internal label (Req 10.1-10.3), so
        // the order advances Pending → Approved → Label_Generated in one action.
        assertThat(response.orderStatus()).isEqualTo(OrderStatus.LABEL_GENERATED);
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.LABEL_GENERATED);

        assertThat(order.getStatusHistory()).hasSize(2);
        // First: approval transition (Req 9.3).
        assertThat(order.getStatusHistory().get(0).getFromStatus())
                .isEqualTo(OrderStatus.PENDING_ADMIN_APPROVAL);
        assertThat(order.getStatusHistory().get(0).getToStatus()).isEqualTo(OrderStatus.APPROVED);
        assertThat(order.getStatusHistory().get(0).getActor()).isEqualTo("admin");
        assertThat(order.getStatusHistory().get(0).getSource()).isEqualTo("ADMIN");
        // Second: label generation transition (Req 10.3), recorded by the system.
        assertThat(order.getStatusHistory().get(1).getFromStatus()).isEqualTo(OrderStatus.APPROVED);
        assertThat(order.getStatusHistory().get(1).getToStatus()).isEqualTo(OrderStatus.LABEL_GENERATED);
        assertThat(order.getStatusHistory().get(1).getSource()).isEqualTo("SYSTEM");
        verify(orderRepository).save(order);
    }

    // --- Reject legal transition + reason stored (Req 9.4) ------------------

    @Test
    void rejectPendingOrderTransitionsToRejectedAndStoresReason() {
        OrderEntity order = orderIn(OrderStatus.PENDING_ADMIN_APPROVAL);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

        OrderResponse response = service.reject(1L, "Payment screenshot unclear", admin);

        assertThat(response.orderStatus()).isEqualTo(OrderStatus.REJECTED);
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.REJECTED);
        assertThat(order.getRejectionReason()).isEqualTo("Payment screenshot unclear");
        assertThat(order.getStatusHistory()).hasSize(1);
        assertThat(order.getStatusHistory().get(0).getToStatus()).isEqualTo(OrderStatus.REJECTED);
    }

    // --- Only legal transitions (Req 8.3) -----------------------------------

    @Test
    void approveNonPendingOrderIsRejectedWith409AndStatusRetained() {
        OrderEntity order = orderIn(OrderStatus.APPROVED);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.approve(1L, admin))
                .isInstanceOf(IllegalStatusTransitionException.class);

        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.APPROVED);
        assertThat(order.getStatusHistory()).isEmpty();
        verify(orderRepository, never()).save(any(OrderEntity.class));
    }

    @Test
    void rejectAlreadyRejectedOrderIsRejectedWith409() {
        OrderEntity order = orderIn(OrderStatus.REJECTED);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.reject(1L, "any reason", admin))
                .isInstanceOf(IllegalStatusTransitionException.class);

        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.REJECTED);
        verify(orderRepository, never()).save(any(OrderEntity.class));
    }

    // --- Paged orders listing (ROADMAP 2.2) ---------------------------------

    @Test
    void listOrdersReturnsPageOfSummaries() {
        OrderEntity order = orderIn(OrderStatus.PENDING_ADMIN_APPROVAL);
        org.springframework.data.domain.Pageable pageable =
                org.springframework.data.domain.PageRequest.of(0, 20);
        when(orderRepository.findAll(
                org.mockito.ArgumentMatchers.<org.springframework.data.jpa.domain.Specification<OrderEntity>>any(),
                any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(
                        List.of(order), pageable, 1));

        org.springframework.data.domain.Page<com.shifa.oms.order.dto.OrderSummaryResponse> page =
                service.listOrders("asha", OrderStatus.PENDING_ADMIN_APPROVAL, null, null, null, pageable);

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent()).singleElement()
                .satisfies(s -> assertThat(s.orderCode()).isEqualTo("SHR-000123"));
    }

    // --- Approval queue returns only pending orders (Req 9.1) ---------------

    @Test
    void approvalQueueReturnsOnlyPendingOrders() {
        OrderEntity pending = orderIn(OrderStatus.PENDING_ADMIN_APPROVAL);
        when(orderRepository.findByOrderStatusOrderByCreatedAtDesc(OrderStatus.PENDING_ADMIN_APPROVAL))
                .thenReturn(List.of(pending));

        List<ApprovalQueueItemResponse> queue = service.approvalQueue();

        assertThat(queue).hasSize(1);
        assertThat(queue.get(0).orderCode()).isEqualTo("SHR-000123");
        assertThat(queue.get(0).customerName()).isEqualTo("Asha");
        assertThat(queue.get(0).totalAmount()).isEqualByComparingTo("240.00");
        assertThat(queue.get(0).paymentScreenshotAvailable()).isFalse();
        verify(orderRepository).findByOrderStatusOrderByCreatedAtDesc(OrderStatus.PENDING_ADMIN_APPROVAL);
    }
}
