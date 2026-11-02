package com.shifa.oms.mail.report;

import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderSource;
import com.shifa.oms.order.PaymentVerificationStatus;
import com.shifa.oms.reconciliation.domain.ReceivableType;
import com.shifa.oms.statemachine.OrderStatus;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * Narrow read-only projections behind the morning email ({@link MorningBriefService}).
 * JPQL on {@link OrderEntity}, so soft-deleted orders stay hidden. Windows are half-open
 * {@code [from, to)}.
 *
 * <p>Definitions are the ones the dashboards already use: sales are order totals, money received is
 * the Settlement Center's "OMS booked" (payments by capture time and receiving account), and cash on
 * delivery is the amount still due on delivery on orders placed in the window.
 */
public interface MorningBriefQueryRepository extends Repository<OrderEntity, Long> {

    /** Orders placed in the window, per source and status. Callers fold rejected and cancelled out. */
    @Query("""
            select o.source as source, o.orderStatus as orderStatus, count(o) as orderCount,
                   sum(o.totalAmount) as sales, sum(o.codAmount) as cod,
                   sum(case when o.codAmount > 0 then 1 else 0 end) as codOrders
            from OrderEntity o
            where o.createdAt >= :from and o.createdAt < :to
            group by o.source, o.orderStatus
            """)
    List<SalesRow> sales(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /**
     * Money captured in the window, by receiving account and by the order's payment-verification
     * state. A null account is a payment with no account recorded (cash, or an older order).
     */
    @Query("""
            select p.bankAccountId as accountId, o.paymentVerificationStatus as verification,
                   count(p) as paymentCount, sum(p.amountReceived) as amount
            from OrderEntity o join o.payments p
            where p.capturedAt >= :from and p.capturedAt < :to
            group by p.bankAccountId, o.paymentVerificationStatus
            """)
    List<ReceiptRow> receipts(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** Best sellers in the window by line value, best first. Rejected and cancelled orders left out. */
    @Query("""
            select li.productName as productName, sum(li.quantity) as units, sum(li.lineTotal) as sales
            from OrderEntity o join o.lineItems li
            where o.createdAt >= :from and o.createdAt < :to
              and o.orderStatus not in :lost
            group by li.productName
            order by sum(li.lineTotal) desc
            """)
    List<ProductRow> productSales(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                  @Param("lost") Collection<OrderStatus> lost);

    /** Customers (by mobile) who ordered in the window. */
    @Query("""
            select distinct o.customerMobile
            from OrderEntity o
            where o.createdAt >= :from and o.createdAt < :to
              and o.orderStatus not in :lost
              and o.customerMobile is not null
            """)
    List<String> mobilesBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                @Param("lost") Collection<OrderStatus> lost);

    /** Of the given customers, those who had an order before {@code before}. */
    @Query("""
            select distinct o.customerMobile
            from OrderEntity o
            where o.createdAt < :before
              and o.orderStatus not in :lost
              and o.customerMobile in :mobiles
            """)
    List<String> mobilesBefore(@Param("before") LocalDateTime before,
                               @Param("lost") Collection<OrderStatus> lost,
                               @Param("mobiles") Collection<String> mobiles);

    /**
     * Orders that moved into one of {@code statuses} during the window (from the status history),
     * each counted once however many matching changes it had.
     */
    @Query("""
            select count(o) as orderCount, sum(o.totalAmount) as amount
            from OrderEntity o
            where exists (select h.id from o.statusHistory h
                          where h.toStatus in :statuses and h.changedAt >= :from and h.changedAt < :to)
            """)
    List<OutcomeRow> movedInto(@Param("statuses") Collection<OrderStatus> statuses,
                               @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** Cash on delivery settled on {@code day}: courier payouts and in-house collections. */
    @Query("""
            select count(r) as receivables, coalesce(sum(coalesce(r.settledAmount, r.amount)), 0) as amount
            from ReceivableEntity r
            where r.type = :type and r.settled = true and r.settledDate = :day
            """)
    List<CodReceivedRow> codReceivedOn(@Param("type") ReceivableType type, @Param("day") LocalDate day);

    interface SalesRow {
        OrderSource getSource();

        OrderStatus getOrderStatus();

        Long getOrderCount();

        BigDecimal getSales();

        BigDecimal getCod();

        Long getCodOrders();
    }

    interface ReceiptRow {
        Long getAccountId();

        PaymentVerificationStatus getVerification();

        Long getPaymentCount();

        BigDecimal getAmount();
    }

    interface ProductRow {
        String getProductName();

        Long getUnits();

        BigDecimal getSales();
    }

    interface OutcomeRow {
        Long getOrderCount();

        BigDecimal getAmount();
    }

    interface CodReceivedRow {
        Long getReceivables();

        BigDecimal getAmount();
    }
}
