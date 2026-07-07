package com.shifa.oms.invoice;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Pure GST tax calculator — no Spring, persistence, or PDF dependencies — so the
 * GST math is fully unit-testable without parsing any rendered output.
 *
 * <p>Given an order's taxable base, the GST rate, whether displayed prices are
 * GST-inclusive, and whether the transaction is intra-state, it produces a
 * {@link GstComputation} with the taxable value, the CGST/SGST or IGST split,
 * the total tax, and the grand total.
 *
 * <h2>Math</h2>
 * <ul>
 *   <li><strong>Prices include GST</strong> ({@code pricesIncludeGst = true}):
 *       the supplied {@code baseAmount} is treated as GST-inclusive, so
 *       {@code taxableValue = baseAmount / (1 + rate/100)} and
 *       {@code totalTax = baseAmount − taxableValue}. The grand total therefore
 *       equals {@code baseAmount} (the order's Total_Amount), keeping the invoice
 *       consistent with the order.</li>
 *   <li><strong>Prices exclude GST</strong> ({@code pricesIncludeGst = false}):
 *       {@code taxableValue = baseAmount} and
 *       {@code totalTax = taxableValue * rate/100}, so
 *       {@code grandTotal = taxableValue + totalTax}.</li>
 * </ul>
 *
 * <h2>Split</h2>
 * <ul>
 *   <li><strong>Intra-state</strong>: CGST and SGST each at half the rate; their
 *       amounts sum to the total tax (any rounding remainder is absorbed into
 *       SGST so CGST + SGST == totalTax exactly).</li>
 *   <li><strong>Inter-state</strong>: a single IGST at the full rate.</li>
 * </ul>
 *
 * <p>All money is rounded to 2 decimals HALF_UP.
 */
public class GstCalculator {

    private static final int MONEY_SCALE = 2;
    private static final int RATE_SCALE = 2;
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal TWO = new BigDecimal("2");

    /**
     * Computes the GST breakdown for a taxable base.
     *
     * @param baseAmount       the order's base amount (Total_Amount); GST-inclusive
     *                         when {@code pricesIncludeGst}, else the net taxable value
     * @param ratePercent      the total GST rate percent (e.g. 5.00); {@code null} treated as 0
     * @param pricesIncludeGst whether {@code baseAmount} already includes GST
     * @param intraState       {@code true} for CGST+SGST, {@code false} for IGST
     * @return the computed GST breakdown (never {@code null})
     */
    public GstComputation calculate(BigDecimal baseAmount, BigDecimal ratePercent,
                                    boolean pricesIncludeGst, boolean intraState) {
        BigDecimal base = baseAmount != null ? baseAmount : BigDecimal.ZERO;
        BigDecimal rate = ratePercent != null ? ratePercent : BigDecimal.ZERO;

        BigDecimal taxableValue;
        BigDecimal totalTax;
        if (pricesIncludeGst) {
            // taxable = base / (1 + rate/100); tax = base - taxable.
            BigDecimal divisor = BigDecimal.ONE.add(rate.divide(HUNDRED, 10, RoundingMode.HALF_UP));
            taxableValue = base.divide(divisor, MONEY_SCALE, RoundingMode.HALF_UP);
            totalTax = money(base).subtract(taxableValue);
        } else {
            taxableValue = money(base);
            totalTax = taxableValue.multiply(rate).divide(HUNDRED, MONEY_SCALE, RoundingMode.HALF_UP);
        }

        BigDecimal grandTotal = taxableValue.add(totalTax);
        BigDecimal halfRate = rate.divide(TWO, RATE_SCALE, RoundingMode.HALF_UP);

        if (intraState) {
            // CGST at half the rate; SGST absorbs any rounding remainder so the
            // two halves sum exactly to the total tax.
            BigDecimal cgst = totalTax.divide(TWO, MONEY_SCALE, RoundingMode.HALF_UP);
            BigDecimal sgst = totalTax.subtract(cgst);
            return new GstComputation(
                    true,
                    scaleRate(rate),
                    taxableValue,
                    halfRate, cgst,
                    halfRate, sgst,
                    zeroRate(), zeroMoney(),
                    totalTax,
                    grandTotal);
        }
        return new GstComputation(
                false,
                scaleRate(rate),
                taxableValue,
                zeroRate(), zeroMoney(),
                zeroRate(), zeroMoney(),
                scaleRate(rate), totalTax,
                totalTax,
                grandTotal);
    }

    private BigDecimal money(BigDecimal value) {
        return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal scaleRate(BigDecimal rate) {
        return rate.setScale(RATE_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal zeroRate() {
        return BigDecimal.ZERO.setScale(RATE_SCALE);
    }

    private BigDecimal zeroMoney() {
        return BigDecimal.ZERO.setScale(MONEY_SCALE);
    }
}
