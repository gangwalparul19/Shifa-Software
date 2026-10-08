package com.shifa.oms.gst.filing.domain;

/**
 * A section of the GSTR-1 outward-supplies return (GST returns &amp; filing, Reqs 3.2, 5.2, 6.1).
 *
 * <ul>
 *   <li>{@link #B2B} — supplies to GST-registered buyers (invoice-level).</li>
 *   <li>{@link #B2CL} — B2C Large: inter-state unregistered-buyer supplies above the threshold.</li>
 *   <li>{@link #B2CS} — B2C Small: summarised unregistered-buyer supplies.</li>
 *   <li>{@link #CDNR} — credit/debit notes issued to registered buyers.</li>
 *   <li>{@link #CDNUR} — credit/debit notes issued to unregistered buyers.</li>
 *   <li>{@link #HSN} — the Table-12 HSN summary.</li>
 *   <li>{@link #DOCS} — the Table-13 document-issued summary.</li>
 * </ul>
 *
 * <p>Only {@link #B2B}, {@link #B2CS}, and {@link #CDNR} have a supported {@link AmendmentTable};
 * corrections to the remaining sections are flagged for manual CA review (Req 3.7). Pure and
 * Spring-free.
 */
public enum Gstr1Section {
    B2B,
    B2CL,
    B2CS,
    CDNR,
    CDNUR,
    HSN,
    DOCS
}
