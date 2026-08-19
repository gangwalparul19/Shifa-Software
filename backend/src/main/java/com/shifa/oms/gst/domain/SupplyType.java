package com.shifa.oms.gst.domain;

/**
 * GST supply classification by place of supply (CA GST dashboard, Req 3.2):
 * <ul>
 *   <li>{@link #INTRA} — destination state == seller state → CGST + SGST.</li>
 *   <li>{@link #INTER} — different state (or seller state unknown) → IGST.</li>
 * </ul>
 */
public enum SupplyType {
    INTRA,
    INTER
}
