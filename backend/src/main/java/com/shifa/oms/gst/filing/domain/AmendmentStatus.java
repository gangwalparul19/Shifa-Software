package com.shifa.oms.gst.filing.domain;

/**
 * The routing lifecycle state of a post-filing correction
 * ({@link com.shifa.oms.gst.filing.ReturnAmendment}), mapped to the {@code return_amendments.status}
 * column (GST returns &amp; filing, Reqs 3.6, 3.7).
 *
 * <ul>
 *   <li>{@link #PENDING} — a correction has been detected against a Filed_Period but no open (non-FILED)
 *       target period exists to land it in yet; it is held without altering any Filed_Period and is
 *       attributed to a target period once one opens (Reqs 3.3, 3.6). The amendment table and target
 *       period are {@code NULL} while pending.</li>
 *   <li>{@link #ROUTED} — the correction has been routed into a supported GSTR-1 amendment section
 *       ({@link AmendmentTable}) and attributed to an open target period (Reqs 3.2, 3.3).</li>
 *   <li>{@link #MANUAL_REVIEW} — the changed section has no supported amendment table
 *       (B2CL, CDNUR, HSN, docs); the correction is flagged for manual CA review rather than routed,
 *       and is never discarded (Req 3.7). The amendment table is {@code NULL}.</li>
 * </ul>
 *
 * <p>Pure and Spring-free; consistent with the other {@code gst.filing.domain} enums.
 */
public enum AmendmentStatus {
    PENDING,
    ROUTED,
    MANUAL_REVIEW
}
