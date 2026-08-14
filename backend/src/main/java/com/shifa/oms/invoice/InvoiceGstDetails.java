package com.shifa.oms.invoice;

import java.util.Objects;

/**
 * The GST-specific block of an {@link InvoiceContent}, present only when GST is
 * enabled in settings. Carries the seller company identity (legal name, GSTIN,
 * address, GST state + code, contacts) shown in the tax-invoice header plus the
 * pure {@link GstComputation} tax breakdown and an optional footer note.
 *
 * <p>When {@link InvoiceContent#gst()} is {@code null} the invoice renders as the
 * plain (pre-GST) invoice; when present the renderer switches to a
 * "TAX INVOICE".
 *
 * @param legalName    the seller's legal name
 * @param gstin        the seller GSTIN
 * @param addressLine  the seller address line
 * @param city         the seller city
 * @param state        the seller GST state
 * @param stateCode    the seller GST state code (e.g. 23)
 * @param contactPhone optional contact phone
 * @param contactEmail optional contact email
 * @param footerNote   optional invoice footer note
 * @param computation  the aggregate GST breakdown (total taxable value + grand total)
 * @param rateGroups   the per-rate GST buckets (one per distinct product GST rate),
 *                     so a mixed-rate basket shows tax broken out per rate
 * @param roundOff     the rounding adjustment so taxable + tax + roundOff == grand total
 *                     (the grand total was rounded to the nearest rupee at order time)
 */
public record InvoiceGstDetails(
        String legalName,
        String gstin,
        String addressLine,
        String city,
        String state,
        String stateCode,
        String contactPhone,
        String contactEmail,
        String footerNote,
        GstComputation computation,
        java.util.List<GstRateGroup> rateGroups,
        java.math.BigDecimal roundOff) {

    public InvoiceGstDetails {
        Objects.requireNonNull(computation, "computation");
        rateGroups = rateGroups == null ? java.util.List.of() : java.util.List.copyOf(rateGroups);
        roundOff = roundOff == null ? java.math.BigDecimal.ZERO : roundOff;
    }

    /** Whether a non-zero rounding adjustment should be shown. */
    public boolean hasRoundOff() {
        return roundOff.signum() != 0;
    }
}
