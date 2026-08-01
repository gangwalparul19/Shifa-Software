package com.shifa.oms.reconciliation.domain;

import com.shifa.oms.order.domain.Money;
import com.shifa.oms.order.domain.PaymentStatus;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.Size;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for reconciliation aggregation, prepaid/COD segregation,
 * and RTO exclusion (Requirements 18.1, 18.2, 18.3, 18.4, 18.6).
 */
class ReconciliationTotalsPropertyTest {

    /** The terminal fate of an order in a reconciliation scenario. */
    enum Outcome { DELIVERED, RTO, LOST, IN_FLIGHT }

    /** An order plus the outcome that drives its settlement side effect. */
    record ScenarioOrder(OrderSettlementView view, Outcome outcome) {
    }

    // Feature: shifa-herbal-remedies, Property 10: Reconciliation totals, segregation, and RTO exclusion
    // **Validates: Requirements 18.1, 18.2, 18.3, 18.4, 18.6**
    @Property
    void reconciliationTotalsSegregationAndRtoExclusion(
            @ForAll @Size(max = 40) List<@net.jqwik.api.From("scenarioOrders") ScenarioOrder> scenario) {

        SettlementProcessor processor = new SettlementProcessor(new AtomicLong(0)::incrementAndGet);
        ReconciliationLedger ledger = new ReconciliationLedger();

        // Apply settlement side effects and record any resulting receivables.
        for (ScenarioOrder so : scenario) {
            SettlementResult result = switch (so.outcome()) {
                case DELIVERED -> processor.onDelivered(so.view());
                case RTO -> processor.onReturnToOrigin(so.view());
                case LOST -> processor.onRedispatch(so.view());
                case IN_FLIGHT -> null; // not yet terminal: no settlement, no receivable
            };
            if (result != null) {
                result.receivable().ifPresent(ledger::record);
            }
        }

        // Determine the couriers that appear so we can check per-courier totals.
        List<Long> couriers = scenario.stream().map(so -> so.view().courierCompanyId()).distinct().toList();

        for (long courier : couriers) {
            // Expected COD total: unsettled COD receivables from DELIVERED, non-prepaid,
            // COD_Amount>0 orders of this courier. RTO and LOST contribute nothing (Req 18.1, 18.6).
            Money expectedCod = Money.ZERO;
            Money expectedClaim = Money.ZERO;
            for (ScenarioOrder so : scenario) {
                if (so.view().courierCompanyId() != courier) {
                    continue;
                }
                if (so.outcome() == Outcome.DELIVERED
                        && so.view().paymentStatus() != PaymentStatus.FULLY_PAID
                        && so.view().codAmount().compareTo(Money.ZERO) > 0) {
                    expectedCod = expectedCod.add(so.view().codAmount());
                }
                if (so.outcome() == Outcome.LOST) {
                    expectedClaim = expectedClaim.add(so.view().totalAmount());
                }
            }

            assertThat(ledger.codReceivableTotal(courier)).isEqualTo(expectedCod);
            assertThat(ledger.claimReceivableTotal(courier)).isEqualTo(expectedClaim);
        }

        // RTO orders never create a receivable, so no receivable exists for an RTO
        // outcome — they are excluded from COD totals (Req 18.6). The number of
        // COD receivables equals the number of delivered COD orders with COD_Amount>0.
        long expectedCodReceivableCount = scenario.stream()
                .filter(so -> so.outcome() == Outcome.DELIVERED
                        && so.view().paymentStatus() != PaymentStatus.FULLY_PAID
                        && so.view().codAmount().compareTo(Money.ZERO) > 0)
                .count();
        assertThat(ledger.all().stream().filter(r -> r.type() == ReceivableType.COD_RECEIVABLE).count())
                .isEqualTo(expectedCodReceivableCount);

        // The unsettled COD list contains exactly the recorded, unsettled COD receivables (Req 18.3).
        assertThat(ledger.unsettledCodReceivables())
                .allMatch(r -> r.type() == ReceivableType.COD_RECEIVABLE && !r.isSettled());

        // Segregation partitions every order into exactly one of prepaid / COD (Req 18.4).
        List<OrderSettlementView> views = scenario.stream().map(ScenarioOrder::view).toList();
        ReconciliationLedger.Segregation segregation = ReconciliationLedger.segregate(views);
        assertThat(segregation.prepaid().size() + segregation.cod().size()).isEqualTo(views.size());
        assertThat(segregation.prepaid()).allMatch(o -> o.paymentStatus() == PaymentStatus.FULLY_PAID);
        assertThat(segregation.cod()).noneMatch(o -> o.paymentStatus() == PaymentStatus.FULLY_PAID);
    }

    @Provide
    Arbitrary<ScenarioOrder> scenarioOrders() {
        Arbitrary<OrderSettlementView> views = SettlementDeliveryRtoPropertyTest.orderArbitrary();
        Arbitrary<Outcome> outcomes = Arbitraries.of(Outcome.values());
        return Combinators.combine(views, outcomes).as(ScenarioOrder::new);
    }
}
