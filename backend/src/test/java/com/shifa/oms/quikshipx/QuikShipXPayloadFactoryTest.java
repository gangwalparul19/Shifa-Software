package com.shifa.oms.quikshipx;

import com.shifa.oms.order.OrderLineItem;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link QuikShipXPayloadFactory#apportionDiscount}: the per-line
 * discount split that makes the courier's products-vs-order-amount check reconcile
 * (Σ(product_amount*qty − product_discount) == order_amount). The shares must sum
 * EXACTLY to the order discount and never exceed a line's own total.
 */
class QuikShipXPayloadFactoryTest {

    private static OrderLineItem line(BigDecimal lineTotal) {
        return new OrderLineItem(null, "Item", 1, lineTotal, lineTotal);
    }

    private static BigDecimal sum(BigDecimal[] shares) {
        BigDecimal s = BigDecimal.ZERO;
        for (BigDecimal v : shares) {
            s = s.add(v);
        }
        return s;
    }

    @Test
    void realStuckOrderDiscountReconciles() {
        // SHR-20260926-D6YN: lines 999.00 + 2199.98 = 3198.98, total 2979.00 =>
        // discount 219.98 apportioned so Σ(lineTotal − share) == total.
        List<OrderLineItem> lines = List.of(
                line(new BigDecimal("999.00")),
                line(new BigDecimal("2199.98")));
        BigDecimal subtotal = new BigDecimal("3198.98");
        BigDecimal discount = new BigDecimal("219.98");

        BigDecimal[] shares = QuikShipXPayloadFactory.apportionDiscount(lines, subtotal, discount);

        assertThat(sum(shares)).isEqualByComparingTo("219.98");
        // Σ(lineTotal − share) must equal the charged total.
        BigDecimal net = subtotal.subtract(sum(shares));
        assertThat(net).isEqualByComparingTo("2979.00");
        // No line's discount exceeds its own total.
        assertThat(shares[0]).isLessThanOrEqualTo(new BigDecimal("999.00"));
        assertThat(shares[1]).isLessThanOrEqualTo(new BigDecimal("2199.98"));
    }

    @Test
    void zeroDiscountYieldsAllZeros() {
        List<OrderLineItem> lines = List.of(line(new BigDecimal("500.00")), line(new BigDecimal("300.00")));
        BigDecimal[] shares = QuikShipXPayloadFactory.apportionDiscount(
                lines, new BigDecimal("800.00"), BigDecimal.ZERO);
        assertThat(sum(shares)).isEqualByComparingTo("0.00");
    }

    @Test
    void sharesSumExactlyEvenWithAwkwardRounding() {
        // Three equal lines, discount 100 => 33.33 * 3 = 99.99, the last line takes
        // the remainder so the total is exactly 100.00.
        List<OrderLineItem> lines = List.of(
                line(new BigDecimal("100.00")),
                line(new BigDecimal("100.00")),
                line(new BigDecimal("100.00")));
        BigDecimal[] shares = QuikShipXPayloadFactory.apportionDiscount(
                lines, new BigDecimal("300.00"), new BigDecimal("100.00"));
        assertThat(sum(shares)).isEqualByComparingTo("100.00");
    }

    private static java.util.Map<String, Object> product(String amount, String qty, String disc) {
        java.util.Map<String, Object> p = new java.util.LinkedHashMap<>();
        p.put("product_amount", amount);
        p.put("product_quantity", qty);
        p.put("product_discount", disc);
        return p;
    }

    @Test
    void floatCheckRejectsSumsThatAreNotDoubleExact() {
        // The two orders QuikShipX kept rejecting: their itemised sums land on
        // 3098.9799999999996 / 3398.9799999999996 in double arithmetic.
        assertThat(QuikShipXPayloadFactory.floatSumMatches(List.of(
                product("999", "1", "31.23"), product("1099.99", "1", "34.39"),
                product("1099.99", "1", "34.38")), "3098.98")).isFalse();
        assertThat(QuikShipXPayloadFactory.floatSumMatches(List.of(
                product("1099.99", "1", "0"), product("1099", "1", "0"),
                product("1099.99", "1", "0"), product("100", "1", "0")), "3398.98")).isFalse();
    }

    @Test
    void floatCheckAcceptsSumsThatQuikShipXAccepted() {
        // Orders that QuikShipX accepted in production.
        assertThat(QuikShipXPayloadFactory.floatSumMatches(List.of(
                product("1099.99", "1", "0"), product("100", "1", "0")), "1199.99")).isTrue();
        assertThat(QuikShipXPayloadFactory.floatSumMatches(List.of(
                product("999", "1", "68.7"), product("1099.99", "2", "151.28")), "2979")).isTrue();
        // A single consolidated line always matches itself.
        assertThat(QuikShipXPayloadFactory.floatSumMatches(List.of(
                product("3098.98", "1", "0")), "3098.98")).isTrue();
    }

    @Test
    void ovgaThreeUnequalLinesDiscountSumsExactly() {
        // The real order SHR-20260925-OVGA that failed with a 1-paisa gap before the
        // fix: lines 999.00 + 1099.99 + 1099.99 = 3198.98, discount 100.00. The
        // shares MUST sum to EXACTLY 100.00 so Σ(lineTotal − share) == 3098.98.
        List<OrderLineItem> lines = List.of(
                line(new BigDecimal("999.00")),
                line(new BigDecimal("1099.99")),
                line(new BigDecimal("1099.99")));
        BigDecimal subtotal = new BigDecimal("3198.98");
        BigDecimal[] shares = QuikShipXPayloadFactory.apportionDiscount(
                lines, subtotal, new BigDecimal("100.00"));

        assertThat(sum(shares)).isEqualByComparingTo("100.00");
        assertThat(subtotal.subtract(sum(shares))).isEqualByComparingTo("3098.98");
    }
}
