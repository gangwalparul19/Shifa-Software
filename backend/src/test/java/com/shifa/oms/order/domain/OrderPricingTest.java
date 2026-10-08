package com.shifa.oms.order.domain;

import com.shifa.oms.common.ValidationException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the pure order pricing + GST engine
 * (product-catalog-pricing-gst Req 6, 7, 8 — Correctness Properties 1-5).
 */
class OrderPricingTest {

    private static OrderPricing.LineInput line(int qty, String rate, String gst) {
        return new OrderPricing.LineInput(qty, new BigDecimal(rate),
                gst == null ? null : new BigDecimal(gst));
    }

    @Test
    void subtotalIsSumOfLineTotalsWithNoDiscount() {
        OrderPricing.PricedOrder priced = OrderPricing.compute(
                List.of(line(2, "1000.00", "5"), line(1, "800.00", "18")),
                OrderPricing.DiscountSpec.NONE);

        assertThat(priced.subtotal()).isEqualByComparingTo("2800.00");
        assertThat(priced.discount()).isEqualByComparingTo("0.00");
        assertThat(priced.total()).isEqualByComparingTo("2800.00");
    }

    @Test
    void gstIsExtractedFromInclusivePricePerLineRate() {
        // 1180 inclusive @18% => base 1000, gst 180.
        OrderPricing.PricedOrder priced = OrderPricing.compute(
                List.of(line(1, "1180.00", "18")), OrderPricing.DiscountSpec.NONE);

        assertThat(priced.lines().get(0).gstAmount()).isEqualByComparingTo("180.00");
        assertThat(priced.gstTotal()).isEqualByComparingTo("180.00");
    }

    @Test
    void zeroOrNullRateLineHasNoGst() {
        OrderPricing.PricedOrder priced = OrderPricing.compute(
                List.of(line(1, "600.00", "0"), line(1, "500.00", null)),
                OrderPricing.DiscountSpec.NONE);

        assertThat(priced.gstTotal()).isEqualByComparingTo("0.00");
    }

    @Test
    void flatDiscountReducesTotalAndApportionsAcrossLines() {
        OrderPricing.PricedOrder priced = OrderPricing.compute(
                List.of(line(1, "1000.00", "5"), line(1, "1000.00", "5")),
                OrderPricing.DiscountSpec.of(DiscountType.FLAT, new BigDecimal("100.00")));

        assertThat(priced.discount()).isEqualByComparingTo("100.00");
        assertThat(priced.total()).isEqualByComparingTo("1900.00");
        // Discount conservation (Property 1): shares sum exactly to the discount.
        BigDecimal shareSum = priced.lines().stream()
                .map(OrderPricing.PricedLineResult::discountShare)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(shareSum).isEqualByComparingTo("100.00");
    }

    @Test
    void percentDiscountResolvesAgainstSubtotal() {
        OrderPricing.PricedOrder priced = OrderPricing.compute(
                List.of(line(1, "1000.00", "5")),
                OrderPricing.DiscountSpec.of(DiscountType.PERCENT, new BigDecimal("10")));

        assertThat(priced.discount()).isEqualByComparingTo("100.00");
        assertThat(priced.total()).isEqualByComparingTo("900.00");
    }

    @Test
    void totalRoundsToWholeRupee() {
        // 3 x 100.10 = 300.30 -> rounds to 300.
        OrderPricing.PricedOrder priced = OrderPricing.compute(
                List.of(line(3, "100.10", "5")), OrderPricing.DiscountSpec.NONE);

        assertThat(priced.total()).isEqualByComparingTo("300.00");
    }

    @Test
    void mixedRateAggregateGstEqualsSumOfLineGst() {
        OrderPricing.PricedOrder priced = OrderPricing.compute(
                List.of(line(1, "1050.00", "5"), line(1, "1180.00", "18")),
                OrderPricing.DiscountSpec.NONE);

        BigDecimal lineGstSum = priced.lines().stream()
                .map(OrderPricing.PricedLineResult::gstAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(priced.gstTotal()).isEqualByComparingTo(lineGstSum);
        // 1050 @5% => 50; 1180 @18% => 180.
        assertThat(priced.gstTotal()).isEqualByComparingTo("230.00");
    }

    @Test
    void percentDiscountOutOfRangeIsRejected() {
        assertThatThrownBy(() -> OrderPricing.compute(
                List.of(line(1, "1000.00", "5")),
                OrderPricing.DiscountSpec.of(DiscountType.PERCENT, new BigDecimal("150"))))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void flatDiscountExceedingSubtotalIsRejected() {
        assertThatThrownBy(() -> OrderPricing.compute(
                List.of(line(1, "1000.00", "5")),
                OrderPricing.DiscountSpec.of(DiscountType.FLAT, new BigDecimal("2000.00"))))
                .isInstanceOf(ValidationException.class);
    }
}
