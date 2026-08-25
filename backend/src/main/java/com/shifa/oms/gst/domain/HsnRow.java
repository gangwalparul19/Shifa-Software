package com.shifa.oms.gst.domain;

import java.math.BigDecimal;

/**
 * One row of the GSTR-1 <strong>Table 12</strong> HSN summary (GST filing compliance, Req 3).
 *
 * <p>This is the filing-ready HSN summary row and is distinct from {@link GstEngine.HsnRow}: it is
 * grouped by the combination of (HSN code, GST rate) (Req 3.5) and additionally carries the UQC
 * (unit of measure) and rate that Table 12 requires (Req 3.1), plus an HSN-length compliance flag
 * (Req 3.4).
 *
 * @param hsn            the HSN code
 * @param uqc            the unit quantity code (defaults to {@code NOS} when the product has none)
 * @param rate           the GST rate percent for this (HSN, rate) group
 * @param quantity       the total quantity across the group
 * @param taxable        the total taxable value across the group
 * @param cgst           the central GST component (intra-state)
 * @param sgst           the state GST component (intra-state)
 * @param igst           the integrated GST component (inter-state)
 * @param compliant      {@code true} when the HSN meets the enforced minimum length (Req 3.4)
 * @param complianceNote a message identifying the product/HSN and required length when non-compliant,
 *                       or {@code null}/blank when compliant
 */
public record HsnRow(
        String hsn,
        String uqc,
        BigDecimal rate,
        BigDecimal quantity,
        BigDecimal taxable,
        BigDecimal cgst,
        BigDecimal sgst,
        BigDecimal igst,
        boolean compliant,
        String complianceNote) {
}
