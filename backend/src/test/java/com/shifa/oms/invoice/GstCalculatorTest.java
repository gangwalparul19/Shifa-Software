package com.shifa.oms.invoice;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Thorough unit tests for the pure {@link GstCalculator}, covering the four
 * combinations of intra/inter-state × inclusive/exclusive pricing plus the
 * key invariants:
 * <ul>
 *   <li>intra-state splits into CGST + SGST each at half the rate;</li>
 *   <li>inter-state produces a single IGST at the full rate;</li>
 *   <li>CGST + SGST (or IGST) sum exactly to the total tax;</li>
 *   <li>grand total = taxable value + total tax, and — when prices are
 *       GST-inclusive — the grand total equals the original base amount so the
 *       invoice stays consistent with the order total.</li>
 * </ul>
 */
class GstCalculatorTest {

    private final GstCalculator calculator = new GstCalculator();

    @Test
    void intraStateInclusiveSplitsIntoCgstAndSgstAndPreservesTotal() {
        GstComputation gst = calculator.calculate(
                new BigDecimal("240.00"), new BigDecimal("5.00"), true, true);

        assertThat(gst.intraState()).isTrue();
        assertThat(gst.cgstRate()).isEqualByComparingTo("2.50");
        assertThat(gst.sgstRate()).isEqualByComparingTo("2.50");
        // 240 / 1.05 = 228.5714 -> 228.57 ; tax = 11.43
        assertThat(gst.taxableValue()).isEqualByComparingTo("228.57");
        assertThat(gst.totalTax()).isEqualByComparingTo("11.43");
        // CGST + SGST sum exactly to the total tax (SGST absorbs the rounding cent).
        assertThat(gst.cgstAmount().add(gst.sgstAmount())).isEqualByComparingTo(gst.totalTax());
        assertThat(gst.igstAmount()).isEqualByComparingTo("0.00");
        // Inclusive: grand total equals the original base amount (order total).
        assertThat(gst.grandTotal()).isEqualByComparingTo("240.00");
    }

    @Test
    void intraStateExclusiveAddsTaxOnTop() {
        GstComputation gst = calculator.calculate(
                new BigDecimal("240.00"), new BigDecimal("5.00"), false, true);

        assertThat(gst.intraState()).isTrue();
        assertThat(gst.taxableValue()).isEqualByComparingTo("240.00");
        assertThat(gst.totalTax()).isEqualByComparingTo("12.00");
        assertThat(gst.cgstAmount()).isEqualByComparingTo("6.00");
        assertThat(gst.sgstAmount()).isEqualByComparingTo("6.00");
        assertThat(gst.grandTotal()).isEqualByComparingTo("252.00");
    }

    @Test
    void interStateInclusiveProducesSingleIgstAndPreservesTotal() {
        GstComputation gst = calculator.calculate(
                new BigDecimal("1000.00"), new BigDecimal("18.00"), true, false);

        assertThat(gst.intraState()).isFalse();
        assertThat(gst.igstRate()).isEqualByComparingTo("18.00");
        // 1000 / 1.18 = 847.4576 -> 847.46 ; tax = 152.54
        assertThat(gst.taxableValue()).isEqualByComparingTo("847.46");
        assertThat(gst.igstAmount()).isEqualByComparingTo("152.54");
        assertThat(gst.totalTax()).isEqualByComparingTo("152.54");
        assertThat(gst.cgstAmount()).isEqualByComparingTo("0.00");
        assertThat(gst.sgstAmount()).isEqualByComparingTo("0.00");
        assertThat(gst.grandTotal()).isEqualByComparingTo("1000.00");
    }

    @Test
    void interStateExclusiveAddsFullIgstOnTop() {
        GstComputation gst = calculator.calculate(
                new BigDecimal("1000.00"), new BigDecimal("18.00"), false, false);

        assertThat(gst.intraState()).isFalse();
        assertThat(gst.taxableValue()).isEqualByComparingTo("1000.00");
        assertThat(gst.igstAmount()).isEqualByComparingTo("180.00");
        assertThat(gst.totalTax()).isEqualByComparingTo("180.00");
        assertThat(gst.grandTotal()).isEqualByComparingTo("1180.00");
    }

    @Test
    void grandTotalAlwaysEqualsTaxablePlusTax() {
        for (String base : new String[] {"0.00", "1.00", "99.99", "12345.67"}) {
            for (String rate : new String[] {"0.00", "5.00", "12.00", "18.00", "28.00"}) {
                for (boolean inclusive : new boolean[] {true, false}) {
                    for (boolean intra : new boolean[] {true, false}) {
                        GstComputation gst = calculator.calculate(
                                new BigDecimal(base), new BigDecimal(rate), inclusive, intra);
                        assertThat(gst.grandTotal())
                                .isEqualByComparingTo(gst.taxableValue().add(gst.totalTax()));
                        BigDecimal splitSum = gst.cgstAmount().add(gst.sgstAmount()).add(gst.igstAmount());
                        assertThat(splitSum).isEqualByComparingTo(gst.totalTax());
                    }
                }
            }
        }
    }

    @Test
    void zeroRateProducesNoTax() {
        GstComputation gst = calculator.calculate(
                new BigDecimal("500.00"), BigDecimal.ZERO, true, true);

        assertThat(gst.totalTax()).isEqualByComparingTo("0.00");
        assertThat(gst.taxableValue()).isEqualByComparingTo("500.00");
        assertThat(gst.grandTotal()).isEqualByComparingTo("500.00");
    }

    // --- Model A: GST added on top, tax derived from taxable + grand total ----

    @Test
    void ofTaxableAndTotalDerivesInterStateTaxFromTheDifference() {
        // Client example: ₹1500 − ₹200 discount = ₹1300 taxable, +5% IGST = ₹65,
        // grand ₹1365. Inter-state (IGST).
        GstComputation gst = calculator.ofTaxableAndTotal(
                new BigDecimal("1300.00"), new BigDecimal("1365.00"),
                new BigDecimal("5.00"), false);

        assertThat(gst.intraState()).isFalse();
        assertThat(gst.taxableValue()).isEqualByComparingTo("1300.00");
        assertThat(gst.totalTax()).isEqualByComparingTo("65.00");
        assertThat(gst.igstAmount()).isEqualByComparingTo("65.00");
        assertThat(gst.igstRate()).isEqualByComparingTo("5.00");
        assertThat(gst.grandTotal()).isEqualByComparingTo("1365.00");
    }

    @Test
    void ofTaxableAndTotalSplitsIntraStateAndReconcilesToGrand() {
        GstComputation gst = calculator.ofTaxableAndTotal(
                new BigDecimal("1300.00"), new BigDecimal("1365.00"),
                new BigDecimal("5.00"), true);

        assertThat(gst.intraState()).isTrue();
        assertThat(gst.cgstAmount().add(gst.sgstAmount())).isEqualByComparingTo("65.00");
        // taxable + tax always equals the supplied grand total (the order total).
        assertThat(gst.taxableValue().add(gst.totalTax())).isEqualByComparingTo(gst.grandTotal());
    }
}
