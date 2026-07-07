package com.shifa.oms.order;

import com.shifa.oms.audit.AuditEvent;
import com.shifa.oms.audit.AuditService;
import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.CurrentUserService;
import com.shifa.oms.auth.Role;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.label.LabelService;
import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.order.dto.OrderResponse;
import com.shifa.oms.platform.storage.StorageService;
import com.shifa.oms.statemachine.OrderStatus;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property-based test for the admin rejection-reason rule (design §Correctness
 * Properties (12), §6.2).
 *
 * Feature: role-based-order-workflow, Property 12: Rejection requires and stores
 * a non-blank reason.
 *
 * **Validates: Requirements 6.4**
 *
 * <p>For any reason string, {@link AdminOrderService#reject} accepts the rejection
 * <em>iff</em> the reason contains at least one non-whitespace character. When
 * accepted, the order transitions to {@code REJECTED} and the trimmed reason is
 * stored; when rejected (blank/{@code null}), a {@link ValidationException} is
 * thrown and the order is left completely unchanged (status retained, no reason,
 * no history row, nothing persisted).
 *
 * <p>Per the Java 25 runtime gotcha, no concrete class is mocked: only the
 * {@link OrderRepository} interface is a Mockito mock; the {@link LabelService},
 * {@link OrderWorkflowService}, and {@link AuditService} collaborators are real
 * instances (the last a small recording subclass). Each {@code @Property} runs
 * the jqwik default of 1000 tries (≥ 100).
 */
class RejectionReasonPropertyTest {

    private final AuthPrincipal admin = new AuthPrincipal(1L, "admin", Role.ADMIN);

    // Feature: role-based-order-workflow, Property 12: Rejection requires and stores a non-blank reason
    // **Validates: Requirements 6.4**
    @Property
    void rejectionIsAcceptedExactlyForNonBlankReasons(@ForAll("reasons") String reason) {
        OrderRepository orderRepository = mock(OrderRepository.class);
        AdminOrderService service = new AdminOrderService(
                orderRepository, labelService(orderRepository), workflow());

        OrderEntity order = pendingOrder();
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        when(orderRepository.save(any(OrderEntity.class))).thenAnswer(i -> i.getArgument(0));

        boolean nonBlank = reason != null && !reason.isBlank();

        if (nonBlank) {
            OrderResponse response = service.reject(1L, reason, admin);

            // Accepted: order rejected and the trimmed reason stored (Req 6.4).
            assertThat(response.orderStatus()).isEqualTo(OrderStatus.REJECTED);
            assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.REJECTED);
            assertThat(order.getRejectionReason()).isEqualTo(reason.trim());
            assertThat(order.getRejectionReason()).isNotBlank();
            // Exactly one status_history row for the rejection transition.
            assertThat(order.getStatusHistory()).hasSize(1);
            assertThat(order.getStatusHistory().get(0).getToStatus()).isEqualTo(OrderStatus.REJECTED);
        } else {
            assertThatThrownBy(() -> service.reject(1L, reason, admin))
                    .isInstanceOf(ValidationException.class);

            // Rejected: the order is left completely unchanged, nothing persisted.
            assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PENDING_ADMIN_APPROVAL);
            assertThat(order.getRejectionReason()).isNull();
            assertThat(order.getStatusHistory()).isEmpty();
            verify(orderRepository, never()).save(any(OrderEntity.class));
        }
    }

    // --- Generators ---------------------------------------------------------

    @Provide
    Arbitrary<String> reasons() {
        // A mix of arbitrary strings (space..'z' → some are whitespace-only) and
        // explicit blank variants, with null injected to exercise the null branch.
        Arbitrary<String> arbitrary = Arbitraries.strings()
                .withCharRange(' ', 'z').ofMinLength(0).ofMaxLength(40);
        Arbitrary<String> blanks = Arbitraries.of("", " ", "   ", "\t", "\n", "  \t \n ");
        return Arbitraries.oneOf(arbitrary, blanks).injectNull(0.15);
    }

    // --- Fixtures -----------------------------------------------------------

    private OrderEntity pendingOrder() {
        OrderEntity order = new OrderEntity(
                "SHR-000123", OrderSource.STOREFRONT, 7L,
                "Asha", "9812345678", "12 MG Road", "Pune", "Maharashtra", "411001");
        order.applyAmounts(new BigDecimal("240.00"), BigDecimal.ZERO,
                new BigDecimal("240.00"), new BigDecimal("240.00"), PaymentStatus.COD);
        order.setOrderStatus(OrderStatus.PENDING_ADMIN_APPROVAL);
        return order;
    }

    private static LabelService labelService(OrderRepository orderRepository) {
        return new LabelService(orderRepository, new StorageService() {
            @Override
            public StoredObjectRef store(String prefix, String originalFilename,
                                         String contentType, byte[] content) {
                return new StoredObjectRef(prefix + "/" + originalFilename);
            }

            @Override
            public Optional<StoredObject> load(String key) {
                return Optional.empty();
            }
        });
    }

    /** Real workflow service with a no-op recording audit (concrete subclass, not a mock). */
    private static OrderWorkflowService workflow() {
        AuditService audit = new AuditService(null, new CurrentUserService()) {
            @Override
            public AuditEvent record(String action, String entityType, String entityId, String summary) {
                return null;
            }

            @Override
            public AuditEvent record(Long actorUserId, String actorUsername, String action,
                                     String entityType, String entityId, String summary) {
                return null;
            }
        };
        return new OrderWorkflowService(audit);
    }
}
