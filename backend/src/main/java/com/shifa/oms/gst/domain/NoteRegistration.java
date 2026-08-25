package com.shifa.oms.gst.domain;

/**
 * GSTR-1 credit/debit note registration category (GST filing compliance, Req 2.2):
 * <ul>
 *   <li>{@link #CDNR} — Credit/Debit Notes (Registered): a note issued against a
 *       <strong>B2B</strong> supply (the original order carried a valid buyer GSTIN).</li>
 *   <li>{@link #CDNUR} — Credit/Debit Notes (Unregistered): a note issued against a
 *       <strong>B2C</strong> supply (B2CL/B2CS — the original order had no buyer GSTIN).</li>
 * </ul>
 *
 * <p>The registration follows the <em>original</em> order's {@link DocumentCategory}:
 * {@code B2B → CDNR}, otherwise {@code CDNUR}.
 */
public enum NoteRegistration {
    CDNR,
    CDNUR
}
