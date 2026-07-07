package com.shifa.oms.reconciliation.domain;

import com.shifa.oms.order.domain.Money;
import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.statemachine.OrderStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Concrete-example unit tests for settlement side effects and the reconciliation
 * ledger, complementing the property-based tests (Requirements 16, 17, 18).
 */
class SettlementProcessorTest {

    private final SettlementProcessor processor =
            new SettlementProcessor(new AtomicLong(0)::incrementAndGet);

    @Test
    void deliveredFullyPaidClosesWithNoReceivable() {
        OrderSettlementView order = new OrderSettlementView(
                10L, 1L, PaymentStatus.FULLY_PAID, Money.of("500.00"), Money.ZERO);

        SettlementResult result = processor.onDelivered(order);

        assertThat(result.newStatus()).isEqualTo(OrderStatus.CLOSED);
        assertThat(result.customerOutstanding()).isEqualTo(Money.ZERO);
        assertThat(result.receivable()).isEmpty();
    }

    @Test
    void deliveredCodCollectsAndRecordsCodReceivable() {
        OrderSettlementView order = new OrderSettlementView(
                11L, 2L, PaymentStatus.COD, Money.of("750.00"), Money.of("750.00"));

        SettlementResult result = processor.onDelivered(order);

        assertThat(result.newStatus()).isEqualTo(OrderStatus.COD_COLLECTED);
        assertThat(result.customerOutstanding()).isEqualTo(Money.ZERO);
        assertThat(result.receivable()).isPresent();
        Receivable receivable = result.receivable().orElseThrow();
        assertThat(receivable.type()).isEqualTo(ReceivableType.COD_RECEIVABLE);
        assertThat(receivable.amount()).isEqualTo(Money.of("750.00"));
    }

    @Test
    void deliveredPartiallyPaidRecordsRemainingAsCodReceivable() {
        OrderSettlementView order = new OrderSettlementView(
                12L, 2L, PaymentStatus.PARTIALLY_PAID, Money.of("1000.00"), Money.of("400.00"));

        SettlementResult result = processor.onDelivered(order);

        assertThat(result.newStatus()).isEqualTo(OrderStatus.COD_COLLECTED);
        assertThat(result.receivable().orElseThrow().amount()).isEqualTo(Money.of("400.00"));
    }

    @Test
    void rtoCancelsCodAndClearsOutstanding() {
        OrderSettlementView order = new OrderSettlementView(
                13L, 3L, PaymentStatus.COD, Money.of("300.00"), Money.of("300.00"));

        SettlementResult result = processor.onReturnToOrigin(order);

        assertThat(result.newStatus()).isEqualTo(OrderStatus.RTO);
        assertThat(result.codAmount()).isEqualTo(Money.ZERO);
        assertThat(result.customerOutstanding()).isEqualTo(Money.ZERO);
        assertThat(result.receivable()).isEmpty();
    }

    @Test
    void courierLostRecordsClaimForNetAmountRegardlessOfPayment() {
        OrderSettlementView prepaid = new OrderSettlementView(
                14L, 3L, PaymentStatus.FULLY_PAID, Money.of("900.00"), Money.ZERO);

        SettlementResult result = processor.onCourierLost(prepaid);

        assertThat(result.newStatus()).isEqualTo(OrderStatus.COURIER_LOST);
        assertThat(result.customerOutstanding()).isEqualTo(Money.ZERO);
        Receivable claim = result.receivable().orElseThrow();
        assertThat(claim.type()).isEqualTo(ReceivableType.CLAIM_RECEIVABLE);
        assertThat(claim.amount()).isEqualTo(Money.of("900.00"));
    }

    @Test
    void ledgerAggregatesPerCourierAndExcludesRto() {
        ReconciliationLedger ledger = new ReconciliationLedger();
        // Courier 1: one COD delivery (500) and one claim (900).
        ledger.record(processor.onDelivered(new OrderSettlementView(
                1L, 1L, PaymentStatus.COD, Money.of("500.00"), Money.of("500.00")))
                .receivable().orElseThrow());
        ledger.record(processor.onCourierLost(new OrderSettlementView(
                2L, 1L, PaymentStatus.FULLY_PAID, Money.of("900.00"), Money.ZERO))
                .receivable().orElseThrow());
        // Courier 1 RTO produces no receivable (excluded from COD totals).
        assertThat(processor.onReturnToOrigin(new OrderSettlementView(
                3L, 1L, PaymentStatus.COD, Money.of("250.00"), Money.of("250.00")))
                .receivable()).isEmpty();

        assertThat(ledger.codReceivableTotal(1L)).isEqualTo(Money.of("500.00"));
        assertThat(ledger.claimReceivableTotal(1L)).isEqualTo(Money.of("900.00"));
        assertThat(ledger.outstandingForCourier(1L)).isEqualTo(Money.of("1400.00"));
        assertThat(ledger.unsettledCodReceivables()).hasSize(1);
    }

    @Test
    void settleReducesOutstandingAndIsIdempotent() {
        ReconciliationLedger ledger = new ReconciliationLedger();
        Receivable cod = new Receivable(1L, 1L, 1L, ReceivableType.COD_RECEIVABLE, Money.of("500.00"));
        ledger.record(cod);

        assertThat(ledger.settle(1L, LocalDate.of(2024, 5, 1))).isTrue();
        assertThat(ledger.outstandingForCourier(1L)).isEqualTo(Money.ZERO);
        assertThat(cod.settledDate()).isEqualTo(LocalDate.of(2024, 5, 1));

        // Idempotent: second settle changes nothing.
        assertThat(ledger.settle(1L, LocalDate.of(2024, 6, 1))).isFalse();
        assertThat(cod.settledDate()).isEqualTo(LocalDate.of(2024, 5, 1));
        assertThat(ledger.outstandingForCourier(1L)).isEqualTo(Money.ZERO);
    }

    @Test
    void settleUnknownReceivableIsRejected() {
        ReconciliationLedger ledger = new ReconciliationLedger();
        assertThatThrownBy(() -> ledger.settle(999L, LocalDate.now()))
                .isInstanceOf(RuntimeException.class);
    }
}
