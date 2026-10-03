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
        /**
         * Matched an order and the amount agreed — either an existing unsettled COD
         * receivable was settled, or (when the order had no receivable yet because
         * the courier's own delivery webhook never arrived) the order was walked
         * forward to Delivered/COD_Collected and a fresh receivable was created and
         * settled in the same action.
         */
        SETTLED,
        /** Matched an unsettled COD receivable but the amount differs — needs review, not settled. */
        MISMATCH,
        /** Every receivable for this order was already settled (e.g. a re-sent/duplicate remittance row). */
        ALREADY_SETTLED,
        /** No order could be resolved from the row's AWB/order code/client order id. */
        ORDER_NOT_FOUND,
        /**
         * The order was found but has no COD receivable at all AND is not awaiting
         * delivery (e.g. prepaid, cancelled, RTO) — nothing to settle, reported for
         * manual review.
         */
        NO_RECEIVABLE,
        /** The row itself was malformed (missing AWB/order code, or an unparsable amount). */
        ERROR
    }
}
