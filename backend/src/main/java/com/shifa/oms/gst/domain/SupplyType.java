package com.shifa.oms.gst.domain;

/**
 * GST supply classification by place of supply (CA GST dashboard, Req 3.2):
 * <ul>
 *   <li>{@link #INTRA} — destination state == seller state → CGST + SGST.</li>
 *   <li>{@link #INTER} — different state (or seller state unknown) → IGST.</li>
 *   <li>{@link #EXPORT} — destination outside India → taxed as IGST. The business
 *       does NOT file a LUT, so exports are a TAXABLE inter-state supply (not
 *       zero-rated), charged at 18% IGST. Segmented separately in the reports.</li>
 * </ul>
 */
public enum SupplyType {
    INTRA,
    INTER,
    EXPORT
}
