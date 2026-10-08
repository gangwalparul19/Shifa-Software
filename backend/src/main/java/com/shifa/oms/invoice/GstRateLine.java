package com.shifa.oms.invoice;

import java.math.BigDecimal;

/**
 * One row of the invoice GST breakup, per distinct GST rate (e.g. "GST @5%",
 * "GST @18%") — the CA-requested per-rate summary. Discount is applied to the
 * line amounts BEFORE this tax is computed, and lines are grouped by rate so the
 * breakup reconciles to the invoice total (mirrors the GSTR-1 rate summary).
 *
 * <p>Intra-state populates {@link #cgstAmount()} + {@link #sgstAmount()} (each at
 * half the rate); inter-state populates {@link #igstAmount()}. All money is
 * scale-2 HALF_UP; {@code cgstAmount + sgstAmount + igstAmount == totalTax}.
 *
 * @param ratePercent  the GST rate for this group (e.g. 5.00, 18.00)
 * @param taxableValue the taxable value at this rate (net of discount)
 * @param cgstAmount   the CGST amount (0 inter-state)
 * @param sgstAmount   the SGST amount (0 inter-state)
 * @param igstAmount   the IGST amount (0 intra-state)
 * @param totalTax     the total tax for this rate group
 */
public record GstRateLine(
        BigDecimal ratePercent,
        BigDecimal taxableValue,
        BigDecimal cgstAmount,
        BigDecimal sgstAmount,
        BigDecimal igstAmount,
        BigDecimal totalTax) {
}
