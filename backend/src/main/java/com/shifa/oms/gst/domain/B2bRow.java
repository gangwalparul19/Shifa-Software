package com.shifa.oms.gst.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One invoice-level row of the GSTR-1 <strong>B2B</strong> section — a supply to a GST-registered
 * buyer (GST filing compliance, Req 5.1).
 *
 * <p>A B2B order emits one {@code B2bRow} per distinct GST rate on the invoice; the tax split follows
 * the order's supply type (intra-state carries equal {@code cgst}/{@code sgst} with zero {@code igst},
 * inter-state carries {@code igst} with zero {@code cgst}/{@code sgst}).
 *
 * @param buyerGstin   the registered buyer's GSTIN
 * @param orderCode    the order's human-readable code (invoice/document reference)
 * @param date         the invoice date
 * @param invoiceValue the GST-inclusive invoice value of the order
 * @param placeOfSupply the place-of-supply state name
 * @param stateCode    the 2-digit GST state code of the place of supply (Req 6.2)
 * @param rate         the GST rate percent for this row
 * @param taxable      the taxable value at this rate
 * @param cgst         the central GST component (intra-state; zero for inter-state)
 * @param sgst         the state GST component (intra-state; zero for inter-state)
 * @param igst         the integrated GST component (inter-state; zero for intra-state)
 */
public record B2bRow(
        String buyerGstin,
        String orderCode,
        LocalDate date,
        BigDecimal invoiceValue,
        String placeOfSupply,
        String stateCode,
        BigDecimal rate,
        BigDecimal taxable,
        BigDecimal cgst,
        BigDecimal sgst,
        BigDecimal igst) {
}
