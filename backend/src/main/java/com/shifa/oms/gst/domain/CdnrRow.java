package com.shifa.oms.gst.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One row of the GSTR-1 <strong>CDNR</strong> section — a credit/debit note issued against a
 * registered-buyer (B2B) supply (GST filing compliance, Req 5.1, Req 2).
 *
 * @param buyerGstin        the registered buyer's GSTIN
 * @param noteNumber        the credit-note number
 * @param noteDate          the note (return/refund) date used for period attribution
 * @param originalOrderCode the code of the order the note is issued against (document reference)
 * @param placeOfSupply     the place-of-supply state name carried from the original order
 * @param stateCode         the 2-digit GST state code of the place of supply (Req 6.2)
 * @param noteValue         the GST-inclusive note value
 * @param rate              the GST rate percent for this row
 * @param taxable           the taxable value at this rate
 * @param cgst              the central GST component (intra-state; zero for inter-state)
 * @param sgst              the state GST component (intra-state; zero for inter-state)
 * @param igst              the integrated GST component (inter-state; zero for intra-state)
 */
public record CdnrRow(
        String buyerGstin,
        String noteNumber,
        LocalDate noteDate,
        String originalOrderCode,
        String placeOfSupply,
        String stateCode,
        BigDecimal noteValue,
        BigDecimal rate,
        BigDecimal taxable,
        BigDecimal cgst,
        BigDecimal sgst,
        BigDecimal igst) {
}
