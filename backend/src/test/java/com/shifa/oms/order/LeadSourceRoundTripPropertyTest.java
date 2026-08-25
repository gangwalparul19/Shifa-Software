package com.shifa.oms.order;

import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.Role;
import com.shifa.oms.order.dto.CreateOrderRequest;
import com.shifa.oms.order.dto.LineItemRequest;
import com.shifa.oms.order.dto.OrderResponse;
import com.shifa.oms.product.ProductRepository;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

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
 * Property-based test that the lead source persists distinctly and survives a
 * round-trip (design §Correctness Properties (9), §3.1).
 *
 * Feature: role-based-order-workflow, Property 9: Lead source persists distinctly
 * and survives round-trip.
 *
 * **Validates: Requirements 4.3, 4.4**
 *
 * <p>For any lead source, optional note, and optional customer email captured on
 * a salesperson order, the persisted order carries back the same
 * {@code leadSource}/{@code leadSourceNote}/{@code customerEmail} (round-trip via
 * {@link OrderResponse#from}); the order-record provenance {@code Order_Source}
 * remains {@code SALESPERSON} and is never aliased with the lead source.
 *
 * <p>Repositories/storage are Mockito-mocked interfaces; concrete collaborators
 * are real (Java 25 gotcha). Each {@code @Property} runs the jqwik default of
 * 1000 tries (≥ 100).
 */
class LeadSourceRoundTripPropertyTest {

    // Feature: role-based-order-workflow, Property 9: Lead source persists distinctly and survives round-trip
    // **Validates: Requirements 4.3, 4.4**
    @Property
    void leadSourceSurvivesRoundTripAndIsDistinctFromOrderSource(
            @ForAll("leadSources") LeadSource leadSource,
            @ForAll("notes") String note,
            @ForAll("emails") String customerEmail) {

        OrderRepository orderRepository = mock(OrderRepository.class);
        ProductRepository productRepository = mock(ProductRepository.class);
        when(productRepository.findById(1L))
                .thenReturn(Optional.of(OrderCreationTestSupport.publishedProduct(1L, "150.00")));
        when(orderRepository.existsByOrderCode(anyString())).thenReturn(false);

        AtomicReference<OrderEntity> saved = new AtomicReference<>();
        when(orderRepository.save(any(OrderEntity.class))).thenAnswer(inv -> {
            OrderEntity e = inv.getArgument(0);
            saved.set(e);
            return e;
        });

        OrderService service = OrderCreationTestSupport.service(orderRepository, productRepository);
        AuthPrincipal actor = new AuthPrincipal(5L, "sales1", Role.SALESPERSON);

        CreateOrderRequest request = new CreateOrderRequest(
                "Asha", "9812345678", "12 MG Road", "Pune", "Maharashtra", "411001",
                List.of(new LineItemRequest(1L, 1, null)),
                BigDecimal.ZERO, null, leadSource, note, customerEmail, null, null, null, null, null);

        OrderResponse response = service.createSalespersonOrder(request, actor);

        // The create response carries the captured lead-source fields.
        assertThat(response.leadSource()).isEqualTo(leadSource);
        assertThat(response.leadSourceNote()).isEqualTo(note);
        assertThat(response.customerEmail()).isEqualTo(customerEmail);
        // Provenance is unchanged and never aliased with the lead source.
        assertThat(response.source()).isEqualTo(OrderSource.SALESPERSON);

        // Round-trip: reprojecting the persisted entity yields the same values.
        OrderEntity persisted = saved.get();
        assertThat(persisted).isNotNull();
        assertThat(persisted.getLeadSource()).isEqualTo(leadSource);
        assertThat(persisted.getLeadSourceNote()).isEqualTo(note);
        assertThat(persisted.getCustomerEmail()).isEqualTo(customerEmail);
        assertThat(persisted.getSource()).isEqualTo(OrderSource.SALESPERSON);

        OrderResponse reloaded = OrderResponse.from(persisted);
        assertThat(reloaded.leadSource()).isEqualTo(leadSource);
        assertThat(reloaded.leadSourceNote()).isEqualTo(note);
        assertThat(reloaded.customerEmail()).isEqualTo(customerEmail);
        assertThat(reloaded.source()).isEqualTo(OrderSource.SALESPERSON);
    }

    @Provide
    Arbitrary<LeadSource> leadSources() {
        return Arbitraries.of(LeadSource.values());
    }

    @Provide
    Arbitrary<String> notes() {
        // Within the 200-char bound (creation would otherwise reject), plus null.
        return Arbitraries.strings()
                .withCharRange('a', 'z').withChars(' ', '.', '-')
                .ofMinLength(0).ofMaxLength(200)
                .injectNull(0.2);
    }

    @Provide
    Arbitrary<String> emails() {
        Arbitrary<String> present = Arbitraries.strings()
                .withCharRange('a', 'z').ofMinLength(3).ofMaxLength(20)
                .map(local -> local + "@example.com");
        return present.injectNull(0.2);
    }
}
