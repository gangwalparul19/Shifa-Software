package com.shifa.oms.gst.filing.domain;

/**
 * A GSTR-1 amendment (correction) section that post-filing corrections are routed into
 * (GST returns &amp; filing, Req 3.2).
 *
 * <ul>
 *   <li>{@link #B2BA} — amends a previously filed <strong>B2B</strong> entry.</li>
 *   <li>{@link #B2CSA} — amends a previously filed <strong>B2CS</strong> entry.</li>
 *   <li>{@link #CDNRA} — amends a previously filed <strong>CDNR</strong> (credit/debit note,
 *       registered) entry.</li>
 * </ul>
 *
 * <p>Corrections to sections without a supported amendment table (B2CL, CDNUR, HSN, docs) are held
 * for manual CA review rather than routed here (Req 3.7). Routing is performed by
 * {@code AmendmentRouter}. Pure and Spring-free.
 */
public enum AmendmentTable {
    B2BA,
    B2CSA,
    CDNRA
}
