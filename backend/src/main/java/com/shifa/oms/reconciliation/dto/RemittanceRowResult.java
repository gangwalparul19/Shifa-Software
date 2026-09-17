package com.shifa.oms.reconciliation.dto;

import java.math.BigDecimal;

/**
 * The per-row outcome of a courier COD remittance CSV import (enhancement:
 * "courier remittance import & auto-match").
 *
 * @param rowNumber        the 1-based data row number (excludes the header row)
 * @param awb              the AWB parsed from the row, if present
 * @param orderCode        the order code parsed from the row, if present
 * @param resolvedOrderCode the order code the row actually matched to (may differ
 *                          from a blank/wrong input {@code orderCode} when matched by AWB)
 * @param remittedAmount   the amount parsed from the row
 * @param expectedAmount   the outstanding COD receivable amount for the matched
 *                          order, or {@code null} when no match was found
 * @param status           what happened to the row
 * @param message          a short human-readable explanation
 */
public record RemittanceRowResult(
        int rowNumber,
        String awb,
        String orderCode,
        String resolvedOrderCode,
        BigDecimal remittedAmount,
        BigDecimal expectedAmount,
        Status status,
        String message
) {

    /** The outcome classification for a single remittance row. */
    public enum Status {
        /** Matched an unsettled COD receivable and the amount matched — settled. */
        SETTLED,
        /** Matched an unsettled COD receivable but the amount differs — needs review, not settled. */
        MISMATCH,
        /** No order could be resolved from the row's AWB/order code. */
        ORDER_NOT_FOUND,
        /** The order was found but has no unsettled COD receivable (e.g. prepaid, or already settled). */
        NO_RECEIVABLE,
        /** The row itself was malformed (missing AWB/order code, or an unparsable amount). */
        ERROR
    }
}
