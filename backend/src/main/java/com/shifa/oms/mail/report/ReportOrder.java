package com.shifa.oms.mail.report;

import com.shifa.oms.statemachine.OrderStatus;

import java.math.BigDecimal;

/**
 * A minimal, immutable projection of an order used to build the consolidated
 * daily report email (Consolidated Daily Report feature).
 *
 * <p>It is the richer sibling of {@link com.shifa.oms.mail.DigestOrder}: in
 * addition to status / total / COD it carries the fields the consolidated
 * report needs to group by salesperson and by customer — {@code createdBy},
 * a resolved {@code salespersonName}, {@code customerName} /
 * {@code customerMobile}, and {@code amountReceived} (prepaid received).
 *
 * <p>{@link DailyReportService} maps each {@code OrderEntity} (and resolves the
 * salesperson name from the user repository) into one of these before calling
 * {@link DailyReport#build}, so the builder is a pure function over plain data
 * and can be unit-tested deterministically without a database, the scheduler,
 * or Mockito on the concrete {@code OrderEntity}.
 *
 * @param status         the order lifecycle status (used to exclude REJECTED / CANCELLED)
 * @param totalAmount    the order total (may be {@code null}; treated as zero)
 * @param codAmount      the cash-on-delivery amount (positive ⇒ COD; may be {@code null})
 * @param amountReceived the prepaid amount received (may be {@code null}; treated as zero)
 * @param customerName   the customer name (may be {@code null})
 * @param customerMobile the customer mobile — the distinct-customer key (may be {@code null}/blank)
 * @param createdBy      the id of the salesperson/user who created the order (may be {@code null})
 * @param salespersonName the resolved salesperson full name/username (may be {@code null})
 */
public record ReportOrder(
        OrderStatus status,
        BigDecimal totalAmount,
        BigDecimal codAmount,
        BigDecimal amountReceived,
        String customerName,
        String customerMobile,
        Long createdBy,
        String salespersonName) {

    /** The order total, never {@code null}. */
    public BigDecimal totalOrZero() {
        return totalAmount == null ? BigDecimal.ZERO : totalAmount;
    }

    /** The COD amount, never {@code null}. */
    public BigDecimal codOrZero() {
        return codAmount == null ? BigDecimal.ZERO : codAmount;
    }

    /** The prepaid amount received, never {@code null}. */
    public BigDecimal receivedOrZero() {
        return amountReceived == null ? BigDecimal.ZERO : amountReceived;
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
