package com.shifa.oms.salesperson;

import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.statemachine.OrderStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * Read-only database aggregates behind "My Day", win-back, reorder-due and the
 * leaderboard, so those views no longer load a salesperson's (or the whole
 * business's) full order history on every dashboard open. JPQL on
 * {@link OrderEntity}, so the soft-delete restriction on the entity applies.
 *
 * <p>Shared filters: {@code excluded} is the set of statuses left out of sales
 * (rejected / payment-rejected / cancelled), and an optional creator scope
 * ({@code anyCreator = true} ignores {@code createdBy}, which must still be
 * non-null, so callers pass {@code -1L}).
 */
public interface MyDayQueryRepository extends Repository<OrderEntity, Long> {

    /** Order count and value of non-excluded orders created in {@code [from, to)}. */
    @Query("""
            select count(o) as orderCount, coalesce(sum(o.totalAmount), 0) as amount
            from OrderEntity o
            where o.orderStatus not in :excluded
              and (:anyCreator = true or o.createdBy = :createdBy)
              and o.createdAt >= :from and o.createdAt < :to
            """)
    TotalsRow totalsBetween(@Param("excluded") Collection<OrderStatus> excluded,
                            @Param("anyCreator") boolean anyCreator,
                            @Param("createdBy") Long createdBy,
                            @Param("from") LocalDateTime from,
                            @Param("to") LocalDateTime to);

    /** As {@link #totalsBetween} but with no upper bound (month-to-date). */
    @Query("""
            select count(o) as orderCount, coalesce(sum(o.totalAmount), 0) as amount
            from OrderEntity o
            where o.orderStatus not in :excluded
              and (:anyCreator = true or o.createdBy = :createdBy)
              and o.createdAt >= :from
            """)
    TotalsRow totalsSince(@Param("excluded") Collection<OrderStatus> excluded,
                          @Param("anyCreator") boolean anyCreator,
                          @Param("createdBy") Long createdBy,
                          @Param("from") LocalDateTime from);

    /** Non-excluded orders still carrying a balance: how many, and the balance owed. */
    @Query("""
            select count(o) as orderCount, coalesce(sum(o.remainingAmount), 0) as amount
            from OrderEntity o
            where o.orderStatus not in :excluded
              and (:anyCreator = true or o.createdBy = :createdBy)
              and o.remainingAmount > 0
            """)
    TotalsRow pendingPayments(@Param("excluded") Collection<OrderStatus> excluded,
                              @Param("anyCreator") boolean anyCreator,
                              @Param("createdBy") Long createdBy);

    /**
     * Lapsed customers: per mobile, order count, lifetime value and last order
     * time, for customers whose last order is before {@code lastBefore}. Highest
     * value first (then most recent), limited by {@code pageable}.
     */
    @Query("""
            select o.customerMobile as mobile, count(o) as orderCount,
                   coalesce(sum(o.totalAmount), 0) as totalValue, max(o.createdAt) as lastOrderAt
            from OrderEntity o
            where o.orderStatus not in :excluded
              and (:anyCreator = true or o.createdBy = :createdBy)
              and length(trim(o.customerMobile)) > 0
            group by o.customerMobile
            having max(o.createdAt) < :lastBefore
            order by sum(o.totalAmount) desc, max(o.createdAt) desc
            """)
    List<WinBackRow> winBackCandidates(@Param("excluded") Collection<OrderStatus> excluded,
                                       @Param("anyCreator") boolean anyCreator,
                                       @Param("createdBy") Long createdBy,
                                       @Param("lastBefore") LocalDateTime lastBefore,
                                       Pageable pageable);

    /**
     * Repeat customers: per mobile with at least two dated orders, the order
     * count, lifetime value and first / last order time (the cadence inputs).
     * Most recently active first.
     */
    @Query("""
            select o.customerMobile as mobile, count(o) as orderCount,
                   coalesce(sum(o.totalAmount), 0) as totalValue,
                   min(o.createdAt) as firstOrderAt, max(o.createdAt) as lastOrderAt
            from OrderEntity o
            where o.orderStatus not in :excluded
              and (:anyCreator = true or o.createdBy = :createdBy)
              and o.createdAt is not null
              and length(trim(o.customerMobile)) > 0
            group by o.customerMobile
            having count(o) >= 2
            order by max(o.createdAt) desc
            """)
    List<CadenceRow> repeatCustomers(@Param("excluded") Collection<OrderStatus> excluded,
                                     @Param("anyCreator") boolean anyCreator,
                                     @Param("createdBy") Long createdBy);

    /**
     * Customer name and order time of every dated, non-excluded order of the
     * given mobiles, newest first, so the caller can take the latest name per
     * mobile.
     */
    @Query("""
            select o.customerMobile as mobile, o.customerName as customerName, o.createdAt as createdAt
            from OrderEntity o
            where o.customerMobile in :mobiles
              and o.orderStatus not in :excluded
              and (:anyCreator = true or o.createdBy = :createdBy)
              and o.createdAt is not null
            order by o.createdAt desc, o.id desc
            """)
    List<NameRow> latestOrderNames(@Param("mobiles") Collection<String> mobiles,
                                   @Param("excluded") Collection<OrderStatus> excluded,
                                   @Param("anyCreator") boolean anyCreator,
                                   @Param("createdBy") Long createdBy);

    /**
     * Revenue and order count per attributed creator for non-excluded orders created in
     * {@code [from, to)}, whole business (not scoped). Most recently active creator first.
     * An "Overall" caller passes a very early {@code from} and a very late {@code to}.
     */
    @Query("""
            select o.createdBy as creatorId, coalesce(sum(o.totalAmount), 0) as revenue,
                   count(o) as orderCount
            from OrderEntity o
            where o.createdBy is not null
              and o.createdAt >= :from
              and o.createdAt < :to
              and o.orderStatus not in :excluded
            group by o.createdBy
            order by max(o.createdAt) desc
            """)
    List<CreatorRow> revenueByCreatorBetween(@Param("from") LocalDateTime from,
                                             @Param("to") LocalDateTime to,
                                             @Param("excluded") Collection<OrderStatus> excluded);

    /**
     * The distinct calendar days on which {@code createdBy} punched an order
     * (any status), for the order streak. Each element is a date; the exact Java
     * type depends on the Hibernate cast, so callers convert defensively.
     */
    @Query("""
            select distinct cast(o.createdAt as date)
            from OrderEntity o
            where o.createdBy = :createdBy and o.createdAt is not null
            """)
    List<Object> orderDates(@Param("createdBy") Long createdBy);

    interface TotalsRow {
        Long getOrderCount();

        BigDecimal getAmount();
    }

    interface WinBackRow {
        String getMobile();

        Long getOrderCount();

        BigDecimal getTotalValue();

        LocalDateTime getLastOrderAt();
    }

    interface CadenceRow {
        String getMobile();

        Long getOrderCount();

        BigDecimal getTotalValue();

        LocalDateTime getFirstOrderAt();

        LocalDateTime getLastOrderAt();
    }

    interface NameRow {
        String getMobile();

        String getCustomerName();

        LocalDateTime getCreatedAt();
    }

    interface CreatorRow {
        Long getCreatorId();

        BigDecimal getRevenue();

        Long getOrderCount();
    }
}
