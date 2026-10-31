package com.shifa.oms.chatbot;

import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderSource;
import com.shifa.oms.order.PaymentVerificationStatus;
import com.shifa.oms.statemachine.OrderStatus;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Read-only aggregates behind the in-house chatbot. Every query is grouped by
 * order status so the service can apply the same rule as the dashboards
 * (sales exclude rejected / payment-rejected / cancelled orders, order counts
 * do not) without a second round trip. JPQL on {@link OrderEntity}, so the
 * soft-delete restriction on the entity (deleted orders are hidden) applies.
 *
 * <p>Filters shared by every query: a half-open {@code [from, to)} window on
 * {@code createdAt}, a set of order sources (the channel), and an optional
 * creator set ({@code anyCreator = true} ignores {@code creatorIds}, which must
 * still be non-empty).
 */
public interface ChatbotQueryRepository extends Repository<OrderEntity, Long> {

    /** Order count and order value per status. */
    @Query("""
            select o.orderStatus as orderStatus, count(o) as orderCount, sum(o.totalAmount) as amount
            from OrderEntity o
            where o.createdAt >= :from and o.createdAt < :to
              and o.source in :sources
              and (:anyCreator = true or o.createdBy in :creatorIds)
            group by o.orderStatus
            """)
    List<StatusRow> totals(@Param("from") LocalDateTime from,
                           @Param("to") LocalDateTime to,
                           @Param("sources") Collection<OrderSource> sources,
                           @Param("anyCreator") boolean anyCreator,
                           @Param("creatorIds") Collection<Long> creatorIds);

    /**
     * As {@link #totals} but only counting the given products' lines: orders that
     * contain them, units, and line value. A line matches by product id, or by
     * name for lines imported without a catalogue product (e.g. from Shopify).
     */
    @Query("""
            select o.orderStatus as orderStatus, count(distinct o.id) as orderCount,
                   sum(li.quantity) as units, sum(li.lineTotal) as amount
            from OrderEntity o join o.lineItems li
            where o.createdAt >= :from and o.createdAt < :to
              and o.source in :sources
              and (:anyCreator = true or o.createdBy in :creatorIds)
              and (li.productId in :productIds or lower(li.productName) in :productNames)
            group by o.orderStatus
            """)
    List<ProductStatusRow> productTotals(@Param("from") LocalDateTime from,
                                         @Param("to") LocalDateTime to,
                                         @Param("sources") Collection<OrderSource> sources,
                                         @Param("anyCreator") boolean anyCreator,
                                         @Param("creatorIds") Collection<Long> creatorIds,
                                         @Param("productIds") Collection<Long> productIds,
                                         @Param("productNames") Collection<String> productNames);

    /** {@link #totals} split by who created the order (null creator = not attributed, e.g. Shopify). */
    @Query("""
            select o.createdBy as personId, o.orderStatus as orderStatus, count(o) as orderCount,
                   sum(o.totalAmount) as amount
            from OrderEntity o
            where o.createdAt >= :from and o.createdAt < :to
              and o.source in :sources
              and (:anyCreator = true or o.createdBy in :creatorIds)
            group by o.createdBy, o.orderStatus
            """)
    List<PersonStatusRow> totalsByPerson(@Param("from") LocalDateTime from,
                                         @Param("to") LocalDateTime to,
                                         @Param("sources") Collection<OrderSource> sources,
                                         @Param("anyCreator") boolean anyCreator,
                                         @Param("creatorIds") Collection<Long> creatorIds);

    /** {@link #productTotals} split by who created the order. */
    @Query("""
            select o.createdBy as personId, o.orderStatus as orderStatus, count(distinct o.id) as orderCount,
                   sum(li.quantity) as units, sum(li.lineTotal) as amount
            from OrderEntity o join o.lineItems li
            where o.createdAt >= :from and o.createdAt < :to
              and o.source in :sources
              and (:anyCreator = true or o.createdBy in :creatorIds)
              and (li.productId in :productIds or lower(li.productName) in :productNames)
            group by o.createdBy, o.orderStatus
            """)
    List<PersonProductStatusRow> productTotalsByPerson(@Param("from") LocalDateTime from,
                                                       @Param("to") LocalDateTime to,
                                                       @Param("sources") Collection<OrderSource> sources,
                                                       @Param("anyCreator") boolean anyCreator,
                                                       @Param("creatorIds") Collection<Long> creatorIds,
                                                       @Param("productIds") Collection<Long> productIds,
                                                       @Param("productNames") Collection<String> productNames);

    /** Units, orders and line value per product name (as recorded on the order line). */
    @Query("""
            select li.productName as productName, o.orderStatus as orderStatus,
                   count(distinct o.id) as orderCount, sum(li.quantity) as units, sum(li.lineTotal) as amount
            from OrderEntity o join o.lineItems li
            where o.createdAt >= :from and o.createdAt < :to
              and o.source in :sources
              and (:anyCreator = true or o.createdBy in :creatorIds)
            group by li.productName, o.orderStatus
            """)
    List<ProductBreakdownRow> totalsByProduct(@Param("from") LocalDateTime from,
                                              @Param("to") LocalDateTime to,
                                              @Param("sources") Collection<OrderSource> sources,
                                              @Param("anyCreator") boolean anyCreator,
                                              @Param("creatorIds") Collection<Long> creatorIds);

    /**
     * Money captured when orders were punched, by receiving account and by the
     * order's payment-verification state. Same basis as the Settlement Center's
     * "OMS booked" figure: payments windowed on {@code capturedAt}, orders that
     * are not soft-deleted, nothing excluded by order status. A null account is a
     * legacy payment with no account recorded.
     */
    @Query("""
            select p.bankAccountId as accountId, o.paymentVerificationStatus as verification,
                   count(p) as paymentCount, sum(p.amountReceived) as amount
            from OrderEntity o join o.payments p
            where p.capturedAt >= :from and p.capturedAt < :to
              and o.source in :sources
              and (:anyCreator = true or o.createdBy in :creatorIds)
            group by p.bankAccountId, o.paymentVerificationStatus
            """)
    List<ReceiptRow> receipts(@Param("from") LocalDateTime from,
                              @Param("to") LocalDateTime to,
                              @Param("sources") Collection<OrderSource> sources,
                              @Param("anyCreator") boolean anyCreator,
                              @Param("creatorIds") Collection<Long> creatorIds);

    /**
     * Cash-on-delivery amount on orders placed in the window, per status. Same
     * basis as the dashboard's "COD due today" ({@code sumCodBetween}): orders
     * with a COD amount, rejected / payment-rejected / cancelled ones excluded by
     * the caller.
     */
    @Query("""
            select o.orderStatus as orderStatus, count(o) as orderCount, sum(o.codAmount) as amount
            from OrderEntity o
            where o.createdAt >= :from and o.createdAt < :to
              and o.source in :sources
              and (:anyCreator = true or o.createdBy in :creatorIds)
              and o.codAmount > 0
            group by o.orderStatus
            """)
    List<StatusRow> codTotals(@Param("from") LocalDateTime from,
                              @Param("to") LocalDateTime to,
                              @Param("sources") Collection<OrderSource> sources,
                              @Param("anyCreator") boolean anyCreator,
                              @Param("creatorIds") Collection<Long> creatorIds);

    /**
     * Orders whose status changed to one of {@code statuses} during the window
     * (read from the status history), grouped by their current status. An order
     * counts once however many matching changes it had.
     */
    @Query("""
            select o.orderStatus as orderStatus, count(o) as orderCount, sum(o.totalAmount) as amount
            from OrderEntity o
            where o.source in :sources
              and (:anyCreator = true or o.createdBy in :creatorIds)
              and exists (select h.id from o.statusHistory h
                          where h.toStatus in :statuses and h.changedAt >= :from and h.changedAt < :to)
            group by o.orderStatus
            """)
    List<StatusRow> movedInto(@Param("from") LocalDateTime from,
                              @Param("to") LocalDateTime to,
                              @Param("statuses") Collection<OrderStatus> statuses,
                              @Param("sources") Collection<OrderSource> sources,
                              @Param("anyCreator") boolean anyCreator,
                              @Param("creatorIds") Collection<Long> creatorIds);

    // ---- single order / customer lookups (derived queries)

    Optional<OrderEntity> findByOrderCode(String orderCode);

    /** Staff often quote just the last characters of an order number. */
    List<OrderEntity> findTop5ByOrderCodeEndingWithIgnoreCaseOrderByCreatedAtDesc(String suffix);

    List<OrderEntity> findTop5ByCustomerMobileOrderByCreatedAtDescIdDesc(String customerMobile);

    long countByCustomerMobile(String customerMobile);

    /** The newest order, used to give the help text a real example order number. */
    Optional<OrderEntity> findFirstByOrderByCreatedAtDescIdDesc();

    Optional<OrderEntity> findById(Long id);

    interface ReceiptRow {
        Long getAccountId();

        PaymentVerificationStatus getVerification();

        Long getPaymentCount();

        BigDecimal getAmount();
    }

    interface StatusRow {
        OrderStatus getOrderStatus();

        Long getOrderCount();

        BigDecimal getAmount();
    }

    interface ProductStatusRow extends StatusRow {
        Long getUnits();
    }

    interface PersonStatusRow extends StatusRow {
        Long getPersonId();
    }

    interface PersonProductStatusRow extends ProductStatusRow {
        Long getPersonId();
    }

    interface ProductBreakdownRow extends ProductStatusRow {
        String getProductName();
    }
}
