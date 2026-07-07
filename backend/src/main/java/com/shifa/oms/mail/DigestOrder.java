package com.shifa.oms.mail;

import com.shifa.oms.statemachine.OrderStatus;

import java.math.BigDecimal;

/**
 * A minimal, immutable projection of an order used to build the daily sales
 * digest (Feature C4).
 *
 * <p>The scheduled job maps each {@code OrderEntity} to one of these before
 * calling {@link DailyDigestJob#buildDigestBody}, so the body builder is a pure
 * function over plain data and can be unit-tested deterministically without a
 * database, the scheduler, or Mockito on the concrete {@code OrderEntity}.
 *
 * @param status       the order lifecycle status (used to exclude REJECTED / CANCELLED)
 * @param totalAmount  the order total (may be {@code null}; treated as zero)
 * @param codAmount    the cash-on-delivery amount (positive ⇒ COD; may be {@code null})
 * @param customerName the customer name (for optional detail; may be {@code null})
 * @param state        the destination state (for optional detail; may be {@code null})
 */
public record DigestOrder(
        OrderStatus status,
        BigDecimal totalAmount,
        BigDecimal codAmount,
        String customerName,
        String state) {

    /** The order total, never {@code null}. */
    public BigDecimal totalOrZero() {
        return totalAmount == null ? BigDecimal.ZERO : totalAmount;
    }

    /** The COD amount, never {@code null}. */
    public BigDecimal codOrZero() {
        return codAmount == null ? BigDecimal.ZERO : codAmount;
    }

    /** Whether this order is cash-on-delivery (positive COD amount). */
    public boolean isCod() {
        return codOrZero().compareTo(BigDecimal.ZERO) > 0;
    }

    /**
     * Whether this order counts toward sales: excludes cancelled / rejected
     * orders, which never produced revenue.
     */
    public boolean countsAsSale() {
        return status != OrderStatus.REJECTED && status != OrderStatus.CANCELLED;
    }
}
