package com.shifa.oms.returns;

/**
 * How a return's refund was paid back to the customer (ENHANCEMENT 2.3,
 * V78 {@code order_returns.refund_method}).
 *
 * <ul>
 *   <li>{@link #CASH} — cash handed/returned.</li>
 *   <li>{@link #UPI} — refunded over UPI.</li>
 *   <li>{@link #BANK_TRANSFER} — bank/NEFT transfer.</li>
 *   <li>{@link #ORIGINAL_PAYMENT} — reversed to the original payment method (card/gateway).</li>
 *   <li>{@link #COD_NOT_COLLECTED} — nothing to refund (a pure COD parcel the customer never paid for).</li>
 * </ul>
 */
public enum RefundMethod {
    CASH,
    UPI,
    BANK_TRANSFER,
    ORIGINAL_PAYMENT,
    COD_NOT_COLLECTED
}
