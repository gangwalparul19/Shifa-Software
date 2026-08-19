package com.shifa.oms.order;

import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.Role;
import com.shifa.oms.order.dto.CreateOrderRequest;
import com.shifa.oms.order.dto.LineItemRequest;
import com.shifa.oms.order.dto.OrderResponse;
import com.shifa.oms.product.ProductRepository;
import com.shifa.oms.statemachine.OrderStatus;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property-based test for deterministic lifecycle initialization on order
 * creation (design §Correctness Properties (2), §6.1).
 *
 * Feature: role-based-order-workflow, Property 2: Creation initializes the
 * lifecycle deterministically.
 *
 * **Validates: Requirements 5.1, 5.3, 5.4, 12.2**
 *
 * <p>For any valid salesperson order-entry request and acting user, creation
 * always starts the order in {@code PENDING_ADMIN_APPROVAL}, records
 * {@code createdBy} as the acting user's id, and appends exactly one synthetic
 * creation history row with {@code from = null} and {@code to =
 * PENDING_ADMIN_APPROVAL}.
 *
 * <p>Repositories/storage are Mockito-mocked interfaces; all concrete
 * collaborators are real (Java 25 gotcha). Each {@code @Property} runs the jqwik
 * default of 1000 tries (≥ 100).
 */
class DeterministicOrderCreationPropertyTest {

    // Feature: role-based-order-workflow, Property 2: Creation initializes the lifecycle deterministically
    // **Validates: Requirements 5.1, 5.3, 5.4, 12.2**
    @Property
    void creationIsDeterministic(
            @ForAll @LongRange(min = 1, max = 9999) long actorUserId,
            @ForAll @IntRange(min = 1, max = 8) int quantity,
            @ForAll("leadSources") LeadSource leadSource) {

        OrderRepository orderRepository = mock(OrderRepository.class);
        ProductRepository productRepository = mock(ProductRepository.class);
        when(productRepository.findById(1L))
                .thenReturn(Optional.of(OrderCreationTestSupport.publishedProduct(1L, "100.00")));
        when(orderRepository.existsByOrderCode(anyString())).thenReturn(false);

        AtomicReference<OrderEntity> saved = new AtomicReference<>();
        when(orderRepository.save(any(OrderEntity.class))).thenAnswer(inv -> {
            OrderEntity e = inv.getArgument(0);
            saved.set(e);
            return e;
        });

        OrderService service = OrderCreationTestSupport.service(orderRepository, productRepository);
        AuthPrincipal actor = new AuthPrincipal(actorUserId, "sales-" + actorUserId, Role.SALESPERSON);

        // COD order (amountReceived = 0) so no payment screenshot is required.
        CreateOrderRequest request = new CreateOrderRequest(
                "Asha", "9812345678", "12 MG Road", "Pune", "Maharashtra", "411001",
                List.of(new LineItemRequest(1L, quantity, null)),
                BigDecimal.ZERO, null, leadSource, null, null, null, null, null, null);

        OrderResponse response = service.createSalespersonOrder(request, actor);

        // Deterministic initial status.
        assertThat(response.orderStatus()).isEqualTo(OrderStatus.PENDING_ADMIN_APPROVAL);

        OrderEntity persisted = saved.get();
        assertThat(persisted).isNotNull();
        assertThat(persisted.getOrderStatus()).isEqualTo(OrderStatus.PENDING_ADMIN_APPROVAL);
        // createdBy is exactly the acting user.
        assertThat(persisted.getCreatedBy()).isEqualTo(actorUserId);

        // Exactly one creation history row: from = null → PENDING_ADMIN_APPROVAL.
        assertThat(persisted.getStatusHistory()).hasSize(1);
        OrderStatusHistory creation = persisted.getStatusHistory().get(0);
        assertThat(creation.getFromStatus()).isNull();
        assertThat(creation.getToStatus()).isEqualTo(OrderStatus.PENDING_ADMIN_APPROVAL);
        assertThat(creation.getActor()).isEqualTo(actor.username());
    }

    @Provide
    Arbitrary<LeadSource> leadSources() {
        return Arbitraries.of(LeadSource.values());
    }
}
