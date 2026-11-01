package com.shifa.oms.dashboard;

import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderSource;
import com.shifa.oms.reconciliation.domain.ReceivableType;
import com.shifa.oms.statemachine.OrderStatus;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Read-only SQL aggregates behind the channel views, so they no longer load
 * every order / receivable and add them up in Java:
 * <ul>
 *   <li>the admin channel summary ({@code ChannelSummaryService}): orders grouped
 *       by source and status, with an optional created-at window and a "this
 *       month" slice;</li>
 *   <li>the channel dashboard's courier-side money
 *       ({@code ChannelDashboardService}): unsettled receivables grouped by type
 *       (and by the owning order's source).</li>
 * </ul>
 * JPQL only. Order queries run on {@link OrderEntity}, so the entity's
 * soft-delete restriction hides deleted orders just as it did for the entity
 * loads these replace.
 */
public interface ChannelAggregateRepository extends Repository<OrderEntity, Long> {

    /** Orders created in the inclusive window {@code [from, to]}, per source and status. */
    @Query("""
            select o.source as orderSource, o.orderStatus as orderStatus, count(o) as orderCount,
                   sum(o.totalAmount) as totalAmount, sum(o.customerOutstanding) as outstandingAmount
            from OrderEntity o
            where o.createdAt >= :from and o.createdAt <= :to
            group by o.source, o.orderStatus
            """)
    List<SourceStatusRow> windowTotals(@Param("from") LocalDateTime from,
                                       @Param("to") LocalDateTime to);

    /** Every order (the unbounded "all time" window), per source and status. */
    @Query("""
            select o.source as orderSource, o.orderStatus as orderStatus, count(o) as orderCount,
                   sum(o.totalAmount) as totalAmount, sum(o.customerOutstanding) as outstandingAmount
            from OrderEntity o
            group by o.source, o.orderStatus
            """)
    List<SourceStatusRow> allTotals();

    /**
     * The part of the window {@code [from, to]} that falls inside the half-open
     * month {@code [monthStart, monthEnd)}, per source and status.
     */
    @Query("""
            select o.source as orderSource, o.orderStatus as orderStatus, count(o) as orderCount,
                   sum(o.totalAmount) as totalAmount, sum(o.customerOutstanding) as outstandingAmount
            from OrderEntity o
            where o.createdAt >= :from and o.createdAt <= :to
              and o.createdAt >= :monthStart and o.createdAt < :monthEnd
            group by o.source, o.orderStatus
            """)
    List<SourceStatusRow> monthTotals(@Param("from") LocalDateTime from,
                                      @Param("to") LocalDateTime to,
                                      @Param("monthStart") LocalDateTime monthStart,
                                      @Param("monthEnd") LocalDateTime monthEnd);

    /**
     * Unsettled receivable amount per receivable type and the source of the
     * order it belongs to. The receivable has no association to its order, so
     * this is an implicit (theta) join; only receivables whose order exists and
     * is not soft-deleted appear, which is exactly what a channel-scoped total needs.
     */
    @Query("""
            select r.type as receivableType, o.source as orderSource, sum(r.amount) as amount
            from ReceivableEntity r, OrderEntity o
            where o.id = r.orderId and o.active = true and r.settled = false
            group by r.type, o.source
            """)
    List<ReceivableSourceRow> unsettledByTypeAndSource();

    /**
     * Unsettled receivable amount per type across ALL receivables, including any
     * whose order is missing or deleted (the unscoped "all channels" total).
     */
    @Query("""
            select r.type as receivableType, sum(r.amount) as amount
            from ReceivableEntity r
            where r.settled = false
            group by r.type
            """)
    List<ReceivableTypeRow> unsettledByType();

    interface SourceStatusRow {
        OrderSource getOrderSource();

        OrderStatus getOrderStatus();

        Long getOrderCount();

        BigDecimal getTotalAmount();

        BigDecimal getOutstandingAmount();
    }

    interface ReceivableTypeRow {
        ReceivableType getReceivableType();

        BigDecimal getAmount();
    }

    interface ReceivableSourceRow extends ReceivableTypeRow {
        OrderSource getOrderSource();
    }
}
