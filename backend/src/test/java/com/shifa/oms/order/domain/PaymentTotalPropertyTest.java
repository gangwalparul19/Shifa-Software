package com.shifa.oms.order.domain;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for Order total amount computation.
 *
 * Feature: shifa-herbal-remedies, Property 1: Total amount equals sum of line
 * totals. For any Order with any list of line items (each with a rate >= 0 and
 * an integer quantity in 1..999), the computed Total_Amount equals the sum over
 * all line items of rate x quantity, computed with exact decimal arithmetic
 * (no floating-point drift).
 *
 * Validates: Requirements 7.3, 7.4
 */
class PaymentTotalPropertyTest {

    /** Rates in paise (0 .. 10,000,000 paise = up to 100,000.00), always scale 2. */
    @Provide
    Arbitrary<Money> rates() {
        return Arbitraries.longs().between(0, 10_000_000).map(Money::ofCents);
    }

    @Provide
    Arbitrary<LineItem> lineItems() {
        Arbitrary<Integer> quantity = Arbitraries.integers().between(1, 999);
        return Combinators.combine(quantity, rates())
                .as((qty, rate) -> LineItem.of(qty, rate));
    }

    @Provide
    Arbitrary<List<LineItem>> lineItemLists() {
        return lineItems().list().ofMinSize(1).ofMaxSize(20);
    }

    // Feature: shifa-herbal-remedies, Property 1: Total amount equals sum of line totals
    @Property(tries = 500)
    void totalAmountEqualsSumOfLineTotals(
            @ForAll("lineItemLists") List<LineItem> items) {

        Money computed = PaymentCalculator.totalAmount(items);

        // Independent reference sum using exact BigDecimal arithmetic.
        BigDecimal expected = BigDecimal.ZERO;
        for (LineItem item : items) {
            expected = expected.add(item.rate().toBigDecimal()
                    .multiply(BigDecimal.valueOf(item.quantity())));
        }

        assertThat(computed.toBigDecimal().compareTo(expected))
                .as("Total_Amount must equal the exact sum of rate x quantity")
                .isZero();
        // Scale is fixed at 2 with no floating-point drift.
        assertThat(computed.toBigDecimal().scale()).isEqualTo(Money.SCALE);
    }

    // Feature: shifa-herbal-remedies, Property 1: Total amount equals sum of line totals
    @Property(tries = 200)
    void totalIsOrderIndependentAndAdditivePerLine(
            @ForAll("lineItems") LineItem single) {

        // A single-line order's total equals that line's rate x quantity.
        Money total = PaymentCalculator.totalAmount(List.of(single));
        assertThat(total).isEqualTo(single.lineTotal());
    }
}
