package com.shifa.oms.gst.domain;

import java.math.BigDecimal;

/**
 * A period order paired with its GSTR-1 supply classification, ready for the {@link Gstr1Builder}
 * (GST filing compliance, Tier 1 — Req 1, 5, 6).
 *
 * <p>This is the pure hand-off between the read-only {@code Gstr1ReturnService} (which reads
 * persisted orders + settings) and the pure {@link Gstr1Builder} (which assembles the section rows).
 * The service resolves each field once from captured data and never rewrites the order's stored line
 * tax snapshots ([A3], Req 1.8):
 *
 * <ul>
 *   <li>{@code order} — the {@link GstEngine.GstOrder} carrying the place-of-supply state, invoice
 *       date, and the immutable GST-inclusive line snapshots (HSN, rate, quantity, line total);</li>
 *   <li>{@code orderCode} — the order's human-readable code (invoice/document reference) used on the
 *       invoice-level B2B / B2CL rows;</li>
 *   <li>{@code buyerGstin} — the optional buyer GSTIN captured on the order ({@code null}/blank for an
 *       unregistered buyer);</li>
 *   <li>{@code supplyType} — intra- vs inter-state, from {@code GstEngine.classify(state, sellerState)};</li>
 *   <li>{@code invoiceValue} — the GST-inclusive order value (Σ line totals), used for the B2CL
 *       threshold and reported on invoice-level rows;</li>
 *   <li>{@code category} — the {@link DocumentCategory} (B2B / B2CL / B2CS) from
 *       {@link GstDocumentClassifier}, which routes the order into its GSTR-1 section.</li>
 * </ul>
 *
 * @param order        the order's GST view (place of supply, date, immutable line snapshots)
 * @param orderCode    the order's human-readable code (invoice/document reference)
 * @param buyerGstin   the buyer's GSTIN, or {@code null}/blank when the buyer is unregistered
 * @param supplyType   intra- vs inter-state supply, from {@code GstEngine.classify}
 * @param invoiceValue the GST-inclusive order value (sum of line totals)
 * @param category     the GSTR-1 document category that routes the order into its section
 */
public record ClassifiedOrder(
        GstEngine.GstOrder order,
        String orderCode,
        String buyerGstin,
        SupplyType supplyType,
        BigDecimal invoiceValue,
        DocumentCategory category) {
}
