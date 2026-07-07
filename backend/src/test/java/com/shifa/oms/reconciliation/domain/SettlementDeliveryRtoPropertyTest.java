package com.shifa.oms.reconciliation.domain;

import com.shifa.oms.order.domain.Money;
import com.shifa.oms.order.domain.PaymentCalculation;
import com.shifa.oms.order.domain.PaymentCalculator;
import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.statemachine.OrderStatus;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for the settlement side effects on delivery and return
 * (Requirements 16.1, 16.2, 16.3).
 */
class SettlementDeliveryRtoPropertyTest {

    // Feature: shifa-herbal-remedies, Property 8: Settlement on delivery and RTO
    // **Validates: Requirements 16.1, 16.2, 16.3**
    @Property
    void settlementOnDeliveryAndRto(@ForAll("orders") OrderSettlementView order) {
        SettlementProcessor processor = new SettlementProcessor(new AtomicLong(0)::incrementAndGet);

        // --- Delivered (Req 16.1, 16.2) ---
        SettlementResult delivered = processor.onDelivered(order);

        // Customer outstanding is always cleared on delivery.
        assertThat(delivered.customerOutstanding()).isEqualTo(Money.ZERO);

        if (order.paymentStatus() == PaymentStatus.FULLY_PAID) {
            // Fully paid → order closes with no receivable (Req 16.1).
            assertThat(delivered.newStatus()).isEqualTo(OrderStatus.CLOSED);
            assertThat(delivered.receivable()).isEmpty();
        } else {
            // COD_Amount > 0 → COD_Collected and a COD receivable equal to COD_Amount (Req 16.2).
            assertThat(order.codAmount().compareTo(Money.ZERO)).isPositive();
            assertThat(delivered.newStatus()).isEqualTo(OrderStatus.COD_COLLECTED);
            assertThat(delivered.receivable()).isPresent();
            Receivable receivable = delivered.receivable().orElseThrow();
            assertThat(receivable.type()).isEqualTo(ReceivableType.COD_RECEIVABLE);
            assertThat(receivable.amount()).isEqualTo(order.codAmount());
            assertThat(receivable.orderId()).isEqualTo(order.orderId());
            assertThat(receivable.courierCompanyId()).isEqualTo(order.courierCompanyId());
            assertThat(receivable.isSettled()).isFalse();
        }

        // --- RTO (Req 16.3) ---
        SettlementResult rto = processor.onReturnToOrigin(order);
        assertThat(rto.newStatus()).isEqualTo(OrderStatus.RTO);
        // COD_Amount is cancelled and outstanding is 0; no receivable is created.
        assertThat(rto.codAmount()).isEqualTo(Money.ZERO);
        assertThat(rto.customerOutstanding()).isEqualTo(Money.ZERO);
        assertThat(rto.receivable()).isEmpty();
    }

    @Provide
    Arbitrary<OrderSettlementView> orders() {
        return orderArbitrary();
    }

    /**
     * Generates a self-consistent order view: the payment status and COD amount
     * are derived from a positive total and an amount received in
     * {@code [0, total]} using the same payment math the rest of the system uses.
     * The total is strictly positive so that every non-fully-paid delivery has a
     * {@code COD_Amount > 0}, matching the two defined delivery-settlement clauses
     * (Req 16.1 fully paid, Req 16.2 COD_Amount &gt; 0).
     */
    static Arbitrary<OrderSettlementView> orderArbitrary() {
        Arbitrary<Long> orderId = Arbitraries.longs().between(1, 100_000);
        Arbitrary<Long> courierId = Arbitraries.longs().between(1, 5);
        Arbitrary<Long> totalCents = Arbitraries.longs().between(1, 1_000_000);
        return Combinators.combine(orderId, courierId, totalCents).as((oid, cid, total) ->
                        new long[]{oid, cid, total})
                .flatMap(parts -> Arbitraries.longs().between(0, parts[2]).map(receivedCents -> {
                    Money total = Money.ofCents(parts[2]);
                    Money received = Money.ofCents(receivedCents);
                    PaymentCalculation calc = PaymentCalculator.classify(total, received);
                    return new OrderSettlementView(
                            parts[0], parts[1], calc.paymentStatus(), total, calc.codAmount());
                }));
    }
}
