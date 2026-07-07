package com.shifa.oms.order.domain;

/**
 * Immutable result of the payment/COD computation for an Order.
 *
 * <p>Invariants (Requirement 7.4, 7.5, 7.7, 7.8, 7.9):
 * <ul>
 *   <li>{@code totalAmount = Σ line_total}</li>
 *   <li>{@code remainingAmount = totalAmount − amountReceived}</li>
 *   <li>{@code paymentStatus} and {@code codAmount} derived from the received/total relationship</li>
 * </ul>
 *
 * <p>Reused by the settlement / receivables ledger (task 5) which reads
 * {@link #codAmount()} and {@link #paymentStatus()} on delivery.
 */
public record PaymentCalculation(
        Money totalAmount,
        Money amountReceived,
        Money remainingAmount,
        Money codAmount,
        PaymentStatus paymentStatus) {
}
