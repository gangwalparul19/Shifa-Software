package com.shifa.oms.payment;

/**
 * Lifecycle of a single online-payment attempt (Phase E).
 *
 * <ul>
 *   <li>{@link #CREATED} — a gateway session was created at {@code initiate};
 *       payment not yet confirmed.</li>
 *   <li>{@link #PAID} — the payment was verified at {@code confirm} and the
 *       order marked fully paid.</li>
 *   <li>{@link #FAILED} — verification failed at {@code confirm}.</li>
 * </ul>
 */
public enum PaymentTransactionStatus {
    CREATED,
    PAID,
    FAILED
}
