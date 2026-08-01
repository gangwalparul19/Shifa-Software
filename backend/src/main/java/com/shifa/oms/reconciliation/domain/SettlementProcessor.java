package com.shifa.oms.reconciliation.domain;

import com.shifa.oms.order.domain.Money;
import com.shifa.oms.statemachine.OrderStatus;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Computes the settlement side effects that accompany an order entering a
 * terminal delivery outcome (Requirement 16.1, 16.2, 16.3, 17.2, 17.3).
 *
 * <p>This is pure domain logic: transition <em>legality</em> is governed by the
 * {@link com.shifa.oms.statemachine.OrderStatusStateMachine} (task&nbsp;4); this
 * class only decides the resulting status, the new customer outstanding amount,
 * the (possibly cancelled) COD amount, and any {@link Receivable} to record.
 *
 * <p>The rules (design: "Settlement side effects on entering a state"):
 * <ul>
 *   <li><b>Delivered + Fully_Paid</b> &rarr; {@code Closed}, outstanding&nbsp;=&nbsp;0,
 *       no receivable (Req 16.1).</li>
 *   <li><b>Delivered + COD_Amount&nbsp;&gt;&nbsp;0</b> &rarr; {@code COD_Collected},
 *       outstanding&nbsp;=&nbsp;0, record a {@code COD_RECEIVABLE} equal to the
 *       COD amount (Req 16.2).</li>
 *   <li><b>RTO</b> &rarr; cancel the COD amount (set 0), outstanding&nbsp;=&nbsp;0,
 *       no receivable — RTO orders are excluded from COD totals (Req 16.3).</li>
 *   <li><b>Redispatch</b> &rarr; record a {@code CLAIM_RECEIVABLE} equal to the
 *       net order amount (prepaid or COD), outstanding&nbsp;=&nbsp;0 (Req 17.2, 17.3).</li>
 * </ul>
 *
 * <p>Newly created receivables get their identity from an injected id generator,
 * so the caller controls id allocation and tests stay deterministic.
 */
public class SettlementProcessor {

    private final LongSupplier receivableIdGenerator;

    /**
     * Creates a processor with an explicit receivable id generator.
     *
     * @param receivableIdGenerator supplies the id for each created receivable
     */
    public SettlementProcessor(LongSupplier receivableIdGenerator) {
        this.receivableIdGenerator = Objects.requireNonNull(receivableIdGenerator, "receivableIdGenerator");
    }

    /** Creates a processor that allocates receivable ids sequentially from 1. */
    public SettlementProcessor() {
        this(new AtomicLong(0)::incrementAndGet);
    }

    /**
     * Applies the settlement side effect for an order reaching {@code Delivered}
     * (Requirement 16.1, 16.2).
     *
     * @param order the order being delivered
     * @return the settlement outcome
     */
    public SettlementResult onDelivered(OrderSettlementView order) {
        Objects.requireNonNull(order, "order");
        if (order.paymentStatus() == com.shifa.oms.order.domain.PaymentStatus.FULLY_PAID) {
            // Fully paid at entry: nothing to collect, order closes (Req 16.1).
            return new SettlementResult(OrderStatus.CLOSED, Money.ZERO, order.codAmount(), Optional.empty());
        }
        if (order.codAmount().compareTo(Money.ZERO) > 0) {
            // Cash was collected on delivery: record the courier's COD receivable (Req 16.2).
            Receivable receivable = new Receivable(
                    receivableIdGenerator.getAsLong(),
                    order.orderId(),
                    order.courierCompanyId(),
                    ReceivableType.COD_RECEIVABLE,
                    order.codAmount());
            return new SettlementResult(
                    OrderStatus.COD_COLLECTED, Money.ZERO, order.codAmount(), Optional.of(receivable));
        }
        // Defensive: no money outstanding and not fully paid should not occur given
        // the payment math, but treat a zero-COD delivery as closed with no receivable.
        return new SettlementResult(OrderStatus.CLOSED, Money.ZERO, order.codAmount(), Optional.empty());
    }

    /**
     * Applies the settlement side effect for an order reaching {@code RTO}: the
     * COD amount is cancelled and the customer outstanding is 0. No receivable is
     * recorded, so the order is excluded from COD totals
     * (Requirement 16.3, 18.6).
     *
     * @param order the returned order
     * @return the settlement outcome
     */
    public SettlementResult onReturnToOrigin(OrderSettlementView order) {
        Objects.requireNonNull(order, "order");
        return new SettlementResult(OrderStatus.RTO, Money.ZERO, Money.ZERO, Optional.empty());
    }

    /**
     * Applies the settlement side effect for an order reaching
     * {@code Redispatch}: a claim receivable equal to the net order amount is
     * recorded regardless of prepaid/COD, and the customer outstanding is 0
     * (Requirement 17.2, 17.3).
     *
     * @param order the lost order
     * @return the settlement outcome, always carrying a claim receivable
     */
    public SettlementResult onRedispatch(OrderSettlementView order) {
        Objects.requireNonNull(order, "order");
        Receivable claim = new Receivable(
                receivableIdGenerator.getAsLong(),
                order.orderId(),
                order.courierCompanyId(),
                ReceivableType.CLAIM_RECEIVABLE,
                order.totalAmount());
        return new SettlementResult(
                OrderStatus.REDISPATCH, Money.ZERO, order.codAmount(), Optional.of(claim));
    }
}
