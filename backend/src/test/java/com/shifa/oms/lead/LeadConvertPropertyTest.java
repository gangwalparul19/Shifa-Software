package com.shifa.oms.lead;

import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.Role;
import com.shifa.oms.common.IllegalLeadTransitionException;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.lead.dto.LeadConvertRequest;
import com.shifa.oms.lead.dto.LeadResponse;
import com.shifa.oms.order.LeadSource;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderService;
import com.shifa.oms.order.OrderSource;
import com.shifa.oms.order.dto.CreateOrderRequest;
import com.shifa.oms.order.dto.LineItemRequest;
import com.shifa.oms.order.dto.OrderResponse;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Property-based test for the convert-to-order path (design §Correctness
 * Properties (7), §Convert Flow).
 *
 * Feature: lead-management, Property 7: Convert marks WON, links the order, and
 * preserves source; failure is a no-op.
 *
 * <p>Over every starting {@link LeadStatus}: a successful convert of a
 * non-terminal lead produces an order carrying the lead's {@link LeadSource} +
 * customer identity (forced by the service, not the client), sets the lead
 * {@code WON} with its {@code convertedOrderId} linked, appends exactly one
 * history row, and records one audit event. A convert of a terminal lead is
 * rejected (409) with no order created and the lead unchanged. When order
 * creation throws, the exception propagates and the lead is left unchanged with
 * no link (the surrounding transaction would roll back in production, Req 4.4).
 *
 * <p>Exercises a real {@link LeadService} over an in-memory repository (interface
 * mock) with a <strong>recording</strong> {@link OrderService} subclass — a real
 * instance, never a Mockito mock of a concrete class (Java 25 gotcha). jqwik
 * default 1000 tries (≥ 100).
 *
 * **Validates: Requirements 4.2, 4.3, 4.4, 4.5, 7.3**
 */
class LeadConvertPropertyTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2024-06-01T00:00:00Z"), ZoneOffset.UTC);
    private static final long OWNER_ID = 7L;
    private static final AuthPrincipal ACTOR = new AuthPrincipal(OWNER_ID, "sales7", Role.SALESPERSON);

    /** A recording {@link OrderService} that captures the request and returns a canned order. */
    static final class RecordingOrderService extends OrderService {
        private final boolean failCreation;
        CreateOrderRequest lastRequest;
        int calls;

        RecordingOrderService(boolean failCreation) {
            super(null, null, null, null, null, null, null, null);
            this.failCreation = failCreation;
        }

        @Override
        public OrderResponse createSalespersonOrder(CreateOrderRequest request, AuthPrincipal actor) {
            this.calls++;
            this.lastRequest = request;
            if (failCreation) {
                throw new ValidationException("Simulated order-creation failure (e.g. out of stock).");
            }
            OrderEntity order = new OrderEntity(
                    "SHR-CONV01", OrderSource.SALESPERSON, actor.userId(),
                    request.customerName(), request.customerMobile(),
                    request.addressLine(), request.city(), request.state(), request.postalCode());
            order.setLeadSource(request.leadSource());
            order.setLeadSourceNote(request.leadSourceNote());
            order.setCustomerEmail(request.customerEmail());
            LeadConvertPropertyTest.setOrderId(order, 5000L);
            return OrderResponse.from(order);
        }
    }

    // Feature: lead-management, Property 7: Convert marks WON, links the order, and preserves source; failure is a no-op
    // **Validates: Requirements 4.2, 4.3, 4.4, 4.5, 7.3**
    @Property
    void convertMarksWonLinksAndPreservesSourceOrIsANoOp(
            @ForAll("statuses") LeadStatus from,
            @ForAll("sources") LeadSource source,
            @ForAll("failOrder") boolean failOrder) {

        List<LeadEntity> store = new ArrayList<>();
        LeadEntity lead = LeadServiceTestSupport.lead(42L, "Asha", source, OWNER_ID, from, null);
        lead.setCustomerMobile("9812345678");
        lead.setCustomerEmail("asha@example.com");
        store.add(lead);
        int historyBefore = lead.getStatusHistory().size();

        AtomicInteger auditCount = new AtomicInteger();
        RecordingOrderService orders = new RecordingOrderService(failOrder);
        LeadService service = LeadServiceTestSupport.service(
                LeadServiceTestSupport.inMemoryRepository(store), auditCount, orders, CLOCK);

        LeadConvertRequest request = new LeadConvertRequest(
                "12 MG Road", "Pune", "Maharashtra", "411001",
                List.of(new LineItemRequest(1L, 1, new BigDecimal("100.00"))),
                new BigDecimal("100.00"), "screenshot-key", null, null, null);

        boolean terminal = LeadStatus.isTerminal(from);

        if (terminal) {
            // Terminal leads reject conversion (409); no order attempted, lead unchanged.
            Throwable thrown = catchThrowable(() -> service.convert(42L, request, ACTOR));
            assertThat(thrown).isInstanceOf(IllegalLeadTransitionException.class);
            assertThat(orders.calls).isZero();
            assertThat(lead.getStatus()).isEqualTo(from);
            assertThat(lead.getConvertedOrderId()).isNull();
            assertThat(lead.getStatusHistory()).hasSize(historyBefore);
            assertThat(auditCount.get()).isZero();
        } else if (failOrder) {
            // Order creation failed → the whole convert is a no-op (Req 4.4).
            Throwable thrown = catchThrowable(() -> service.convert(42L, request, ACTOR));
            assertThat(thrown).isInstanceOf(ValidationException.class);
            assertThat(orders.calls).isEqualTo(1);
            assertThat(lead.getStatus()).isEqualTo(from);
            assertThat(lead.getConvertedOrderId()).isNull();
            assertThat(lead.getStatusHistory()).hasSize(historyBefore);
            assertThat(auditCount.get()).isZero();
        } else {
            LeadResponse response = service.convert(42L, request, ACTOR);

            // The order carried the lead's forced customer + lead source (Req 4.2, 4.3).
            assertThat(orders.calls).isEqualTo(1);
            assertThat(orders.lastRequest.customerName()).isEqualTo("Asha");
            assertThat(orders.lastRequest.customerMobile()).isEqualTo("9812345678");
            assertThat(orders.lastRequest.customerEmail()).isEqualTo("asha@example.com");
            assertThat(orders.lastRequest.leadSource()).isEqualTo(source);

            // The lead is WON, linked to the created order, with one new history row + audit.
            assertThat(response.status()).isEqualTo(LeadStatus.WON);
            assertThat(lead.getStatus()).isEqualTo(LeadStatus.WON);
            assertThat(response.convertedOrderId()).isEqualTo(5000L);
            assertThat(lead.getStatusHistory()).hasSize(historyBefore + 1);
            LeadStatusHistory row = lead.getStatusHistory().get(lead.getStatusHistory().size() - 1);
            assertThat(row.getFromStatus()).isEqualTo(from);
            assertThat(row.getToStatus()).isEqualTo(LeadStatus.WON);
            assertThat(row.getActor()).isEqualTo("sales7");
            assertThat(auditCount.get()).isEqualTo(1);
        }
    }

    @Provide
    Arbitrary<LeadStatus> statuses() {
        return Arbitraries.of(LeadStatus.values());
    }

    @Provide
    Arbitrary<LeadSource> sources() {
        return Arbitraries.of(LeadSource.values());
    }

    @Provide
    Arbitrary<Boolean> failOrder() {
        return Arbitraries.of(true, false);
    }

    /** Reflectively assigns an order id on the canned {@link OrderEntity} fixture. */
    static void setOrderId(OrderEntity order, long id) {
        try {
            java.lang.reflect.Field field = OrderEntity.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(order, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to set order id in test fixture", e);
        }
    }
}
