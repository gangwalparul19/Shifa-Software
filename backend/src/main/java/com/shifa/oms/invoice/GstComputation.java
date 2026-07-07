package com.shifa.oms.invoice;

import java.math.BigDecimal;

/**
 * The result of a GST computation over an order's taxable base — a pure,
 * render-agnostic value object produced by {@link GstCalculator} and asserted on
 * directly in unit tests (no PDF bytes parsed).
 *
 * <p>Exactly one of the two tax splits is populated:
 * <ul>
 *   <li><strong>intra-state</strong> ({@link #intraState()} = {@code true}) →
 *       {@link #cgstAmount()} and {@link #sgstAmount()} are set (each computed at
 *       half the configured rate), and {@link #igstAmount()} is zero;</li>
 *   <li><strong>inter-state</strong> ({@link #intraState()} = {@code false}) →
 *       {@link #igstAmount()} is set at the full rate, and CGST/SGST are zero.</li>
 * </ul>
 *
 * <p>All monetary fields are scale-2 {@link BigDecimal} (HALF_UP). Invariants:
 * {@code totalTax = cgstAmount + sgstAmount + igstAmount} and
 * {@code grandTotal = taxableValue + totalTax}.
 *
 * @param intraState   whether the order is intra-state (CGST+SGST) vs inter-state (IGST)
 * @param ratePercent  the total GST rate percent applied (e.g. 5.00)
 * @param taxableValue the taxable base (net of GST)
 * @param cgstRate     the CGST rate percent (rate/2 intra-state, else 0)
 * @param cgstAmount   the CGST amount (0 for inter-state)
 * @param sgstRate     the SGST rate percent (rate/2 intra-state, else 0)
 * @param sgstAmount   the SGST amount (0 for inter-state)
 * @param igstRate     the IGST rate percent (full rate inter-state, else 0)
 * @param igstAmount   the IGST amount (0 for intra-state)
 * @param totalTax     the total tax (CGST+SGST or IGST)
 * @param grandTotal   the taxable value plus total tax
 */
public record GstComputation(
        boolean intraState,
        BigDecimal ratePercent,
        BigDecimal taxableValue,
        BigDecimal cgstRate,
        BigDecimal cgstAmount,
        BigDecimal sgstRate,
        BigDecimal sgstAmount,
        BigDecimal igstRate,
        BigDecimal igstAmount,
        BigDecimal totalTax,
        BigDecimal grandTotal) {
}
