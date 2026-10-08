package com.shifa.oms.gst.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A credit/debit note derived from a refunded order return, ready for the GSTR-1
 * CDNR/CDNUR sections (GST filing compliance, Req 2.1, 2.5).
 *
 * <p>The note is a pure projection of a return against its original order: the refund
 * amount is treated as a GST-inclusive value from which the taxable value and GST split
 * are extracted using the same extraction as outward supplies. The split follows the
 * original order's supply type — intra-state notes carry equal {@code cgst}/{@code sgst}
 * with zero {@code igst}, inter-state notes carry {@code igst} with zero
 * {@code cgst}/{@code sgst}.
 *
 * @param returnId            the source order-return id
 * @param originalOrderId     the id of the order the note is issued against (document reference)
 * @param originalOrderCode   the human-readable code of the original order (document reference)
 * @param noteDate            the note (return/refund) date, used for period attribution (Req 2.3)
 * @param registration        CDNR when the original order is B2B, else CDNUR (Req 2.2)
 * @param placeOfSupplyState  the place-of-supply state name carried from the original order (Req 2.5)
 * @param stateCode           the 2-digit GST state code of the place of supply (Req 2.5)
 * @param supplyType          intra- vs inter-state, from the original order
 * @param noteValue           the GST-inclusive refund amount
 * @param taxable             the taxable value extracted from {@code noteValue}
 * @param cgst                the central GST component (intra-state; zero for inter-state)
 * @param sgst                the state GST component (intra-state; zero for inter-state)
 * @param igst                the integrated GST component (inter-state; zero for intra-state)
 */
public record CreditNote(
        Long returnId,
        Long originalOrderId,
        String originalOrderCode,
        LocalDate noteDate,
        NoteRegistration registration,
        String placeOfSupplyState,
        String stateCode,
        SupplyType supplyType,
        BigDecimal noteValue,
        BigDecimal taxable,
        BigDecimal cgst,
        BigDecimal sgst,
        BigDecimal igst) {

    /** Total GST of the note = CGST + SGST + IGST. */
    public BigDecimal totalTax() {
        return cgst.add(sgst).add(igst);
    }
}
