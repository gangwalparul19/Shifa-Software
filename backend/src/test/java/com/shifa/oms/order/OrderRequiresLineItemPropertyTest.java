package com.shifa.oms.order;

import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.Role;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.order.dto.CreateOrderRequest;
import com.shifa.oms.order.dto.LineItemRequest;
import com.shifa.oms.product.ProductRepository;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property-based test that an order must carry at least one line item (design
 * §Correctness Properties (11), §6.1).
 *
 * Feature: role-based-order-workflow, Property 11: Orders require at least one
 * line item.
 *
 * **Validates: Requirements 5.2**
 *
 * <p>For any acting user and (valid) lead source, submitting a salesperson order
 * with an empty line-item list is rejected with a {@link ValidationException} and
 * nothing is persisted (the order repository's {@code save} is never invoked).
 *
 * <p>Repositories/storage are Mockito-mocked interfaces; concrete collaborators
 * are real (Java 25 gotcha). Each {@code @Property} runs the jqwik default of
 * 1000 tries (≥ 100).
 */
class OrderRequiresLineItemPropertyTest {

    // Feature: role-based-order-workflow, Property 11: Orders require at least one line item
    // **Validates: Requirements 5.2**
    @Property
    void emptyLineItemsAreRejectedAndNothingPersisted(
            @ForAll("leadSources") LeadSource leadSource) {

        OrderRepository orderRepository = mock(OrderRepository.class);
        ProductRepository productRepository = mock(ProductRepository.class);
        when(orderRepository.existsByOrderCode(anyString())).thenReturn(false);
        when(orderRepository.save(any(OrderEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        OrderService service = OrderCreationTestSupport.service(orderRepository, productRepository);
        AuthPrincipal actor = new AuthPrincipal(5L, "sales1", Role.SALESPERSON);

        CreateOrderRequest request = new CreateOrderRequest(
                "Asha", "9812345678", "12 MG Road", "Pune", "Maharashtra", "411001",
                List.<LineItemRequest>of(),
                BigDecimal.ZERO, null, leadSource, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> service.createSalespersonOrder(request, actor))
                .isInstanceOf(ValidationException.class);

        // Nothing persisted for a line-item-less order.
        verify(orderRepository, never()).save(any(OrderEntity.class));
    }

    // A non-empty order with the same valid inputs is accepted — confirms the
    // rejection above is due to the missing line item, not the lead source.
    // Feature: role-based-order-workflow, Property 11: Orders require at least one line item
    // **Validates: Requirements 5.2**
    @Property
    void nonEmptyLineItemsAreAccepted(@ForAll("leadSources") LeadSource leadSource) {
        OrderRepository orderRepository = mock(OrderRepository.class);
        ProductRepository productRepository = mock(ProductRepository.class);
        when(productRepository.findById(1L))
                .thenReturn(java.util.Optional.of(
                        OrderCreationTestSupport.publishedProduct(1L, "100.00")));
        when(orderRepository.existsByOrderCode(anyString())).thenReturn(false);
        when(orderRepository.save(any(OrderEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        OrderService service = OrderCreationTestSupport.service(orderRepository, productRepository);
        AuthPrincipal actor = new AuthPrincipal(5L, "sales1", Role.SALESPERSON);

        CreateOrderRequest request = new CreateOrderRequest(
                "Asha", "9812345678", "12 MG Road", "Pune", "Maharashtra", "411001",
                List.of(new LineItemRequest(1L, 1, null)),
                BigDecimal.ZERO, null, leadSource, null, null, null, null, null, null, null);

        assertThat(service.createSalespersonOrder(request, actor)).isNotNull();
        verify(orderRepository).save(any(OrderEntity.class));
    }

    @Provide
    Arbitrary<LeadSource> leadSources() {
        return Arbitraries.of(LeadSource.values());
    }
}
