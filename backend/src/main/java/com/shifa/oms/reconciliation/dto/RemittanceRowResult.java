package com.shifa.oms.reconciliation.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The per-row outcome of a courier COD remittance CSV import (enhancement:
 * "courier remittance import & auto-match"; extended in V99 for partial
 * settlement + the persisted weekly report).
 *
 * @param rowNumber         the 1-based data row number (excludes the header row)
 * @param awb               the AWB parsed from the row, if present
 * @param orderCode         the order code parsed from the row, if present
 * @param clientOrderId     the courier's client order id parsed from the row, if present
 * @param customerName      the customer name parsed from the row, if present
 * @param resolvedOrderCode the order code the row actually matched to (may differ
 *                          from a blank/wrong input {@code orderCode} when matched by AWB)
 * @param resolvedOrderId   the internal id of the matched order, or {@code null} if unmatched
 * @param remittedAmount    the amount parsed from the row
 * @param expectedAmount    the outstanding COD receivable amount for the matched
 *                          order, or {@code null} when no match was found
 * @param settledAmount     the amount actually settled against the receivable (0 on a dry run,
 *                          mismatch, or unmatched row; the remitted amount on a full/partial settle)
 * @param shortfallAmount   {@code expectedAmount - settledAmount} when short-paid (never negative)
 * @param remittedDate      the remittance date parsed from the row, if present
 * @param status            what happened to the row
 * @param message           a short human-readable explanation
 */
public record RemittanceRowResult(
        int rowNumber,
        String awb,
        String orderCode,
        String clientOrderId,
        String customerName,
        String resolvedOrderCode,
        Long resolvedOrderId,
        BigDecimal remittedAmount,
        BigDecimal expectedAmount,
        BigDecimal settledAmount,
        BigDecimal shortfallAmount,
        LocalDate remittedDate,
        Status status,
        String message
) {

    /**
     * Backward-compatible factory mirroring the original 8-arg shape (kept for the
     * service's error/not-found paths that don't carry the richer fields). The
     * client order id / customer name / resolved id / settled + shortfall / date
     * default to null/zero.
     */
    public static RemittanceRowResult of(int rowNumber, String awb, String orderCode, String resolvedOrderCode,
                                         BigDecimal remittedAmount, BigDecimal expectedAmount,
                                         Status status, String message) {
        return new RemittanceRowResult(rowNumber, awb, orderCode, null, null, resolvedOrderCode, null,
                remittedAmount, expectedAmount, BigDecimal.ZERO, BigDecimal.ZERO, null, status, message);
    }

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
        /**
         * Matched an unsettled COD receivable and the remittance was SHORT of the
         * expected amount: the receivable is settled for the remitted amount and the
         * {@link #shortfallAmount} stays outstanding, flagged for follow-up (V99).
         */
        PARTIALLY_SETTLED,
        /**
         * Matched an unsettled COD receivable but the remittance OVER-paid beyond
         * tolerance — needs review, not settled (we never over-credit on a guess).
         */
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
