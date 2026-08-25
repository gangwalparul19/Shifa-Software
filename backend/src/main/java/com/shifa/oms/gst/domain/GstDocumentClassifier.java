package com.shifa.oms.gst.domain;

import java.math.BigDecimal;

/**
 * Classifies an outward supply into a {@link DocumentCategory} for GSTR-1
 * (GST filing compliance, Req 1.4–1.8).
 *
 * <p>This is a <strong>pure function of captured data</strong> — the buyer GSTIN, the
 * {@link SupplyType} (intra/inter, as determined by {@code GstEngine.classify}), and the
 * order's GST-inclusive invoice value. It never reads or modifies the order's stored line
 * tax snapshots (Req 1.8).
 *
 * <p>Classification rules, in order:
 * <ol>
 *   <li>a valid buyer GSTIN → {@link DocumentCategory#B2B} (Req 1.4);</li>
 *   <li>otherwise, an inter-state supply with invoice value strictly greater than
 *       ₹2,50,000 → {@link DocumentCategory#B2CL} (Req 1.5);</li>
 *   <li>otherwise → {@link DocumentCategory#B2CS} (Req 1.6).</li>
 * </ol>
 *
 * <p>Existing orders with no buyer GSTIN classify by rules 2 and 3, so no historical order
 * requires a GSTIN to be reportable (Req 1.7).
 */
public final class GstDocumentClassifier {

    /**
     * The B2C Large threshold: an inter-state unregistered-buyer supply is B2CL only when its
     * invoice value is <strong>strictly greater</strong> than this amount (₹2,50,000).
     */
    public static final BigDecimal B2CL_THRESHOLD = new BigDecimal("250000");

    private GstDocumentClassifier() {
    }

    /**
     * Classify an order's supply category from its captured data.
     *
     * @param buyerGstin   the buyer's GSTIN, or {@code null}/blank when the buyer is unregistered
     * @param supplyType   intra- vs inter-state supply (from {@code GstEngine.classify})
     * @param invoiceValue the GST-inclusive order value (sum of line totals)
     * @return exactly one of {@link DocumentCategory#B2B}, {@link DocumentCategory#B2CL},
     *         {@link DocumentCategory#B2CS} — never {@code null}
     */
    public static DocumentCategory classify(String buyerGstin, SupplyType supplyType,
                                            BigDecimal invoiceValue) {
        if (Gstin.isValid(buyerGstin)) {
            return DocumentCategory.B2B;
        }
        if (supplyType == SupplyType.INTER
                && invoiceValue != null
                && invoiceValue.compareTo(B2CL_THRESHOLD) > 0) {
            return DocumentCategory.B2CL;
        }
        return DocumentCategory.B2CS;
    }
}
