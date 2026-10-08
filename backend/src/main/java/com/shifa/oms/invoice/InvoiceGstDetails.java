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
 * @param computation  the aggregate GST tax breakdown (total taxable, CGST/SGST or IGST, totals)
 * @param rateBreakup  the per-rate breakup rows (GST @5%, @18%, …); reconciles to {@code computation}
 * @param pricesIncludeGst whether prices are GST-inclusive (tax within) vs added on top
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
        java.util.List<GstRateLine> rateBreakup,
        boolean pricesIncludeGst) {

    public InvoiceGstDetails {
        Objects.requireNonNull(computation, "computation");
        rateBreakup = rateBreakup == null ? java.util.List.of() : java.util.List.copyOf(rateBreakup);
    }
}
