package com.shifa.oms.order.domain;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property tests for {@link OrderPricing} (product-catalog-pricing-gst
 * Correctness Properties 1-3): discount conservation, GST aggregation, and the
 * per-line GST extraction bound, over randomised orders and flat discounts.
 */
class OrderPricingPropertyTest {

    private static final BigDecimal[] RATES = {
            null, new BigDecimal("0"), new BigDecimal("5"), new BigDecimal("18")
    };

    @Property(tries = 500)
    void discountConservationAndGstAggregation(
            @ForAll @Size(min = 1, max = 6) List<@IntRange(min = 1, max = 5) Integer> quantities,
            @ForAll @IntRange(min = 0, max = 100) int discountPercentOfSubtotal) {

        List<OrderPricing.LineInput> lines = new ArrayList<>(quantities.size());
        BigDecimal subtotal = BigDecimal.ZERO;
        for (int i = 0; i < quantities.size(); i++) {
            int qty = quantities.get(i);
            BigDecimal rate = new BigDecimal(100 + i * 37);
            BigDecimal gst = RATES[i % RATES.length];
            lines.add(new OrderPricing.LineInput(qty, rate, gst));
            subtotal = subtotal.add(rate.multiply(BigDecimal.valueOf(qty)));
        }
        // A flat discount that is always within [0, subtotal].
        BigDecimal flat = subtotal.multiply(BigDecimal.valueOf(discountPercentOfSubtotal))
                .divide(new BigDecimal("100"), 2, java.math.RoundingMode.DOWN);

        OrderPricing.PricedOrder priced = OrderPricing.compute(
                lines, OrderPricing.DiscountSpec.of(DiscountType.FLAT, flat));

        // Property 1: discount shares sum EXACTLY to the resolved discount.
        BigDecimal shareSum = priced.lines().stream()
                .map(OrderPricing.PricedLineResult::discountShare)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(shareSum).isEqualByComparingTo(priced.discount());

        // Property 2: aggregate GST equals the sum of per-line GST amounts.
        BigDecimal gstSum = priced.lines().stream()
                .map(OrderPricing.PricedLineResult::gstAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(priced.gstTotal()).isEqualByComparingTo(gstSum);

        // Property 3: each line's GST is within [0, net].
        for (OrderPricing.PricedLineResult line : priced.lines()) {
            assertThat(line.gstAmount()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
            assertThat(line.gstAmount()).isLessThanOrEqualTo(line.netAmount());
        }

        // Property 4: total == round(subtotal - discount).
        BigDecimal expectedTotal = priced.subtotal().subtract(priced.discount())
                .setScale(0, java.math.RoundingMode.HALF_UP);
        assertThat(priced.total()).isEqualByComparingTo(expectedTotal);
    }
}
