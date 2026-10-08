package com.shifa.oms.reconciliation.domain;

import com.shifa.oms.order.domain.Money;
import com.shifa.oms.statemachine.OrderStatus;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for claim generation on courier loss
 * (Requirements 17.2, 17.3).
 */
class LossClaimPropertyTest {

    // Feature: shifa-herbal-remedies, Property 9: Loss produces a claim for the full net amount
    // **Validates: Requirements 17.2, 17.3**
    @Property
    void lossProducesClaimForFullNetAmount(@ForAll("orders") OrderSettlementView order) {
        SettlementProcessor processor = new SettlementProcessor(new AtomicLong(0)::incrementAndGet);

        SettlementResult lost = processor.onRedispatch(order);

        assertThat(lost.newStatus()).isEqualTo(OrderStatus.REDISPATCH);

        // Regardless of prepaid or COD, a claim receivable equal to the net order
        // amount is recorded (Req 17.2).
        assertThat(lost.receivable()).isPresent();
        Receivable claim = lost.receivable().orElseThrow();
        assertThat(claim.type()).isEqualTo(ReceivableType.CLAIM_RECEIVABLE);
        assertThat(claim.amount()).isEqualTo(order.totalAmount());
        assertThat(claim.orderId()).isEqualTo(order.orderId());
        assertThat(claim.courierCompanyId()).isEqualTo(order.courierCompanyId());
        assertThat(claim.isSettled()).isFalse();

        // The customer outstanding for the order is set to 0 (Req 17.3).
        assertThat(lost.customerOutstanding()).isEqualTo(Money.ZERO);
    }

    @Provide
    Arbitrary<OrderSettlementView> orders() {
        return SettlementDeliveryRtoPropertyTest.orderArbitrary();
    }
}
