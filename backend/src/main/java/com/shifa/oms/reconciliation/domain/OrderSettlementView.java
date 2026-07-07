package com.shifa.oms.reconciliation.domain;

import com.shifa.oms.order.domain.Money;
import com.shifa.oms.order.domain.PaymentStatus;

import java.util.Objects;

/**
 * The minimal, persistence-free projection of an Order that the settlement and
 * reconciliation logic needs (Requirement 16, 17, 18). It carries the order's
 * identity, its assigned courier company, its payment classification, and its
 * money amounts.
 *
 * <p>Keeping this a small immutable value lets the settlement side effects
 * (task&nbsp;5.1) and the reconciliation ledger (task&nbsp;5.2) be exercised
 * exhaustively by property-based tests without any database.
 *
 * @param orderId          the order identifier
 * @param courierCompanyId the courier company handling the order
 * @param paymentStatus    the order's payment classification (never {@code null})
 * @param totalAmount      the net order amount (Σ line totals); never {@code null}
 * @param codAmount        the amount to be collected on delivery; never {@code null}
 */
public record OrderSettlementView(
        long orderId,
        long courierCompanyId,
        PaymentStatus paymentStatus,
        Money totalAmount,
        Money codAmount) {

    public OrderSettlementView {
        Objects.requireNonNull(paymentStatus, "paymentStatus");
        Objects.requireNonNull(totalAmount, "totalAmount");
        Objects.requireNonNull(codAmount, "codAmount");
    }

    /** Whether this order is prepaid (fully paid at entry). */
    public boolean isPrepaid() {
        return paymentStatus == PaymentStatus.FULLY_PAID;
    }
}
