package com.shifa.oms.ledger.autopost;

/**
 * The kinds of Shifa OMS business documents that auto-posting derives a ledger voucher from.
 *
 * <p>Each value is the {@code source_type} recorded on both the posted {@link com.shifa.oms.ledger.Voucher}
 * ({@code vouchers.source_type}) and its {@link SourcePostingLog} trace row
 * ({@code ledger_source_postings.source_type}); the enum name is persisted verbatim as the string key
 * (Reqs 8.3, 9.2, 10.2, 11.3). Modelling the four documents as an enum gives the auto-posting services
 * (idempotency guard, draft builder, drainer) a single type-safe vocabulary while the underlying columns
 * stay simple strings.
 *
 * <ul>
 *   <li>{@link #ORDER} — a finalised sales order/invoice (Sales voucher, Req 8).</li>
 *   <li>{@link #PURCHASE_ORDER} — a recorded purchase bill (Purchase voucher, Req 9).</li>
 *   <li>{@link #EXPENSE} — a recorded expense (Payment/Journal voucher, Req 10).</li>
 *   <li>{@link #PAYMENT} — a customer receipt or supplier payment (Receipt/Payment voucher, Req 11).</li>
 * </ul>
 */
public enum SourceType {

    /** A finalised sales order/invoice (Sales voucher, Req 8). */
    ORDER,

    /** A recorded purchase bill (Purchase voucher, Req 9). */
    PURCHASE_ORDER,

    /** A recorded expense (Payment/Journal voucher, Req 10). */
    EXPENSE,

    /** A customer receipt or supplier payment (Receipt/Payment voucher, Req 11). */
    PAYMENT
}
