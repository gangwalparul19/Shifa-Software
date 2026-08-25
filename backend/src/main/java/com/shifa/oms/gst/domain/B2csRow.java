package com.shifa.oms.gst.domain;

import java.math.BigDecimal;

/**
 * One aggregated row of the GSTR-1 <strong>B2CS</strong> (B2C Small) section — summarised
 * unregistered-buyer supplies (GST filing compliance, Req 5.1).
 *
 * <p>B2CS supplies are reported summarised (not invoice-level): rows are aggregated by the
 * combination of place-of-supply, supply type, and GST rate. The tax split follows the supply type
 * (intra-state carries equal {@code cgst}/{@code sgst} with zero {@code igst}, inter-state carries
 * {@code igst} with zero {@code cgst}/{@code sgst}).
 *
 * @param type          the GST Offline Tool B2CS type code, {@code "OE"} (over-the-counter)
 * @param placeOfSupply the place-of-supply state name
 * @param stateCode     the 2-digit GST state code of the place of supply (Req 6.2)
 * @param supplyType    intra- vs inter-state classification for this aggregate
 * @param rate          the GST rate percent for this row
 * @param taxable       the total taxable value at this (place, type, rate) combination
 * @param cgst          the central GST component (intra-state; zero for inter-state)
 * @param sgst          the state GST component (intra-state; zero for inter-state)
 * @param igst          the integrated GST component (inter-state; zero for intra-state)
 */
public record B2csRow(
        String type,
        String placeOfSupply,
        String stateCode,
        SupplyType supplyType,
        BigDecimal rate,
        BigDecimal taxable,
        BigDecimal cgst,
        BigDecimal sgst,
        BigDecimal igst) {
}
