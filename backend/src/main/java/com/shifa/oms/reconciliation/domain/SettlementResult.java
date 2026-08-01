package com.shifa.oms.reconciliation.domain;

import com.shifa.oms.order.domain.Money;
import com.shifa.oms.statemachine.OrderStatus;

import java.util.Objects;
import java.util.Optional;

/**
 * The immutable outcome of applying a settlement side effect to an order when it
 * enters {@code Delivered}, {@code RTO} or {@code Redispatch}
 * (Requirement 16.1, 16.2, 16.3, 17.2, 17.3).
 *
 * @param newStatus            the resulting order status (Closed / COD_Collected
 *                             / RTO / Redispatch)
 * @param customerOutstanding  the order's customer outstanding amount after
 *                             settlement (always 0 for these transitions)
 * @param codAmount            the order's COD amount after settlement (cancelled
 *                             to 0 on RTO; otherwise unchanged)
 * @param receivable           the receivable created by this settlement, if any
 *                             (COD receivable on COD delivery, claim receivable
 *                             on loss); empty otherwise
 */
public record SettlementResult(
        OrderStatus newStatus,
        Money customerOutstanding,
        Money codAmount,
        Optional<Receivable> receivable) {

    public SettlementResult {
        Objects.requireNonNull(newStatus, "newStatus");
        Objects.requireNonNull(customerOutstanding, "customerOutstanding");
        Objects.requireNonNull(codAmount, "codAmount");
        Objects.requireNonNull(receivable, "receivable");
    }
}
