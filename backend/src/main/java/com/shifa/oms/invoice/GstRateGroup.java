package com.shifa.oms.invoice;

import java.math.BigDecimal;

/**
 * One GST-rate bucket of a tax invoice, so a basket mixing rates (e.g. some
 * products at 5% and others at 18%) shows the tax broken out per rate — the
 * correct GST presentation. Lines are grouped by their GST rate; each group's
 * taxable value is its share of the (post-discount) taxable base and its tax is
 * that taxable × rate.
 *
 * <p>Exactly one split is populated: intra-state groups carry {@code cgstAmount}
 * + {@code sgstAmount} (each at half the rate); inter-state groups carry
 * {@code igstAmount} at the full rate.
 *
 * @param ratePercent  the GST rate for this group (e.g. 5.00, 18.00)
 * @param intraState   whether this is a CGST+SGST (intra) or IGST (inter) group
 * @param taxableValue the group's taxable value (net of discount)
 * @param cgstAmount   the CGST amount (0 for inter-state)
 * @param sgstAmount   the SGST amount (0 for inter-state)
 * @param igstAmount   the IGST amount (0 for intra-state)
 * @param totalTax     the group's total tax (CGST+SGST or IGST)
 */
public record GstRateGroup(
        BigDecimal ratePercent,
        boolean intraState,
        BigDecimal taxableValue,
        BigDecimal cgstAmount,
        BigDecimal sgstAmount,
        BigDecimal igstAmount,
        BigDecimal totalTax) {
}
