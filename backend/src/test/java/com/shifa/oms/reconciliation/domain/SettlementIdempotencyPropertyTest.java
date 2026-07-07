package com.shifa.oms.reconciliation.domain;

import com.shifa.oms.order.domain.Money;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test that settling a receivable reduces the courier's
 * outstanding by exactly the settled amount and is idempotent
 * (Requirement 18.5).
 */
class SettlementIdempotencyPropertyTest {

    /** A generated receivable specification (before id assignment). */
    record ReceivableSpec(long courierCompanyId, ReceivableType type, long amountCents) {
    }

    // Feature: shifa-herbal-remedies, Property 11: Settling a receivable reduces outstanding and is idempotent
    // **Validates: Requirements 18.5**
    @Property
    void settlingReducesOutstandingAndIsIdempotent(
            @ForAll @Size(min = 1, max = 30) List<@net.jqwik.api.From("receivableSpecs") ReceivableSpec> specs,
            @ForAll @IntRange(min = 0, max = 1_000) int pick,
            @ForAll("settlementDates") LocalDate date) {

        ReconciliationLedger ledger = new ReconciliationLedger();
        long id = 1;
        for (ReceivableSpec spec : specs) {
            ledger.record(new Receivable(
                    id++, id, spec.courierCompanyId(), spec.type(), Money.ofCents(spec.amountCents())));
        }

        // Choose one receivable to settle.
        List<Receivable> all = ledger.all();
        Receivable target = all.get(Math.floorMod(pick, all.size()));
        long courier = target.courierCompanyId();

        Money outstandingBefore = ledger.outstandingForCourier(courier);

        // First settlement: reduces outstanding by exactly the settled amount and records the date.
        boolean firstSettle = ledger.settle(target.id(), date);
        assertThat(firstSettle).isTrue();
        assertThat(target.isSettled()).isTrue();
        assertThat(target.settledDate()).isEqualTo(date);

        Money outstandingAfter = ledger.outstandingForCourier(courier);
        assertThat(outstandingAfter).isEqualTo(outstandingBefore.subtract(target.amount()));

        // Second settlement of the same receivable is a no-op (idempotent): the
        // outstanding does not drop further and the recorded date is retained.
        boolean secondSettle = ledger.settle(target.id(), date.plusDays(1));
        assertThat(secondSettle).isFalse();
        assertThat(target.settledDate()).isEqualTo(date);
        assertThat(ledger.outstandingForCourier(courier)).isEqualTo(outstandingAfter);
    }

    @Provide
    Arbitrary<ReceivableSpec> receivableSpecs() {
        Arbitrary<Long> courier = Arbitraries.longs().between(1, 4);
        Arbitrary<ReceivableType> type = Arbitraries.of(ReceivableType.values());
        Arbitrary<Long> amount = Arbitraries.longs().between(0, 500_000);
        return Combinators.combine(courier, type, amount).as(ReceivableSpec::new);
    }

    @Provide
    Arbitrary<LocalDate> settlementDates() {
        return Arbitraries.longs().between(0, 3_650)
                .map(days -> LocalDate.of(2024, 1, 1).plusDays(days));
    }
}
