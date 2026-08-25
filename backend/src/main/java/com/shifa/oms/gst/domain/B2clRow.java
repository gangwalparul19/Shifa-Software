package com.shifa.oms.gst.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One invoice-level row of the GSTR-1 <strong>B2CL</strong> (B2C Large) section — an inter-state
 * supply to an unregistered buyer with invoice value greater than ₹2,50,000 (GST filing compliance,
 * Req 5.1).
 *
 * <p>B2CL supplies are always inter-state, so the tax is reported as {@code igst} only (there is no
 * CGST/SGST split).
 *
 * @param orderCode     the order's human-readable code (invoice/document reference)
 * @param date          the invoice date
 * @param invoiceValue  the GST-inclusive invoice value of the order
 * @param placeOfSupply the place-of-supply state name
 * @param stateCode     the 2-digit GST state code of the place of supply (Req 6.2)
 * @param rate          the GST rate percent for this row
 * @param taxable       the taxable value at this rate
 * @param igst          the integrated GST component (B2CL is always inter-state)
 */
public record B2clRow(
        String orderCode,
        LocalDate date,
        BigDecimal invoiceValue,
        String placeOfSupply,
        String stateCode,
        BigDecimal rate,
        BigDecimal taxable,
        BigDecimal igst) {
}
