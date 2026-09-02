package com.shifa.oms.order;

import com.shifa.oms.statemachine.OrderStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository for the {@link OrderEntity} aggregate.
 *
 * <p>The search finder (Req 22.1) matches a term against customer name, mobile,
 * order code, numeric id, or courier AWB, and is role-scoped by an optional
 * {@code createdBy} constraint injected from {@link com.shifa.oms.auth.SalespersonScopeResolver}
 * (Req 5.5): a salesperson passes their own id, an admin/accountant passes
 * {@code null} to see everything. Duplicate detection (Req 22.2) counts prior
 * orders for a mobile number.
 */
public interface OrderRepository extends JpaRepository<OrderEntity, Long>,
        JpaSpecificationExecutor<OrderEntity> {

    /** True when an order already uses this generated order code. */
    boolean existsByOrderCode(String orderCode);

    /**
     * The order whose {@code order_code} equals this value (the value encoded in
     * the internal-label barcode), or empty when no order matches. Backs the
     * packing barcode scan lookup (Req 11.1, 11.3).
     */
    Optional<OrderEntity> findByOrderCode(String orderCode);

    /**
     * All orders in a given lifecycle status, most recent first. Used to build
     * the admin approval queue of {@code Pending_Admin_Approval} orders (Req 9.1).
     */
    List<OrderEntity> findByOrderStatusOrderByCreatedAtDesc(OrderStatus orderStatus);

    /**
     * All orders in a given lifecycle status, oldest first (FIFO). Backs the
     * packing work queues (awaiting packing / handover / dispatch) so the packer
     * clears the oldest orders first.
     */
    List<OrderEntity> findByOrderStatusOrderByCreatedAtAsc(OrderStatus orderStatus);

    /**
     * All orders in a given payment-verification state, oldest first (FIFO).
     * Backs the Payment Verifier's queue of prepaid payments awaiting
     * authenticity checks (product-audit §4.4).
     */
    List<OrderEntity> findByPaymentVerificationStatusOrderByCreatedAtAsc(
            PaymentVerificationStatus paymentVerificationStatus);

    /**
     * All orders in any of the given lifecycle statuses, most recent first. Backs
     * the reconciliation prepaid/COD segregation view over fulfilled orders
     * (Req 18.4).
     */
    List<OrderEntity> findByOrderStatusInOrderByCreatedAtDesc(java.util.Collection<OrderStatus> statuses);

    /** Count of prior orders for a customer mobile number (Req 22.2). */
    long countByCustomerMobile(String customerMobile);

    /**
     * The most recent order for a customer mobile number, used to pre-fill the
     * New Order form's customer + shipping details from the customer's last order.
     */
    java.util.Optional<OrderEntity> findFirstByCustomerMobileOrderByCreatedAtDescIdDesc(String customerMobile);

    /**
     * Best-selling product ids across the whole business (by units sold), for the
     * order-entry "favorites" quick-add. Excludes rejected/cancelled orders.
     */
    @Query(value = """
            SELECT li.product_id
            FROM line_items li
            JOIN orders o ON o.id = li.order_id
            WHERE li.product_id IS NOT NULL
              AND o.order_status NOT IN ('REJECTED','CANCELLED')
            GROUP BY li.product_id
            ORDER BY SUM(li.quantity) DESC
            """, nativeQuery = true)
    List<Long> topSoldProductIds(Pageable pageable);

    /** As {@link #topSoldProductIds} but limited to a single salesperson's orders. */
    @Query(value = """
            SELECT li.product_id
            FROM line_items li
            JOIN orders o ON o.id = li.order_id
            WHERE li.product_id IS NOT NULL
              AND o.created_by = :createdBy
              AND o.order_status NOT IN ('REJECTED','CANCELLED')
            GROUP BY li.product_id
            ORDER BY SUM(li.quantity) DESC
            """, nativeQuery = true)
    List<Long> topSoldProductIdsByCreator(@Param("createdBy") Long createdBy, Pageable pageable);

    /**
     * Products frequently bought in the SAME order as any of {@code productIds}
     * (co-occurrence), ranked by how many orders they co-occur in — powers the
     * "frequently bought together" upsell. Excludes the input products themselves
     * and rejected/cancelled orders.
     */
    @Query(value = """
            SELECT li2.product_id
            FROM line_items li1
            JOIN line_items li2 ON li2.order_id = li1.order_id AND li2.product_id <> li1.product_id
            JOIN orders o ON o.id = li1.order_id
            WHERE li1.product_id IN (:productIds)
              AND li2.product_id IS NOT NULL
              AND li2.product_id NOT IN (:productIds)
              AND o.order_status NOT IN ('REJECTED','CANCELLED')
            GROUP BY li2.product_id
            ORDER BY COUNT(DISTINCT o.id) DESC
            """, nativeQuery = true)
    List<Long> relatedProductIds(@Param("productIds") Collection<Long> productIds, Pageable pageable);

    /**
     * All orders created within an inclusive timestamp window, used by the P&L
     * report (Feature C3) to sum revenue. The finance service filters out
     * REJECTED / CANCELLED orders when summing revenue.
     */
    List<OrderEntity> findByCreatedAtBetween(java.time.LocalDateTime from, java.time.LocalDateTime to);

    /**
     * Orders for a customer mobile number, most recent first (Req 15.1 agent
     * lookup by mobile). May match several orders for a repeat customer.
     */
    List<OrderEntity> findByCustomerMobileOrderByCreatedAtDesc(String customerMobile);

    /** An order visible to a salesperson only when they created it (Req 5.5). */
    Optional<OrderEntity> findByIdAndCreatedBy(Long id, Long createdBy);

    /**
     * An order visible to a team lead only when it was created by one of their
     * assigned salespeople ({@code created_by IN (:createdByIds)}). Backs
     * team-scoped order detail/invoice access. Callers must not pass an empty
     * collection (a team lead with no members is short-circuited to "not found").
     */
    Optional<OrderEntity> findByIdAndCreatedByIn(Long id, java.util.Collection<Long> createdByIds);

    /**
     * Role-scoped search over name / mobile / order code / id / AWB (Req 22.1).
     * A {@code null} {@code createdBy} disables scoping (admin/accountant); a
     * non-null value restricts to that salesperson's own orders (Req 5.5).
     *
     * <p>Implemented as a native query with a {@code LEFT JOIN} to
     * {@code courier_records} so the AWB is searchable before the courier module
     * (task 14) introduces its own entity.
     */
    @Query(value = """
            SELECT DISTINCT o.* FROM orders o
            LEFT JOIN courier_records cr ON cr.order_id = o.id
            WHERE (:createdBy IS NULL OR o.created_by = :createdBy)
              AND ( LOWER(o.customer_name)  LIKE CONCAT('%', LOWER(:term), '%')
                 OR o.customer_mobile       LIKE CONCAT('%', :term, '%')
                 OR LOWER(o.order_code)     LIKE CONCAT('%', LOWER(:term), '%')
                 OR CAST(o.id AS CHAR)      LIKE CONCAT('%', :term, '%')
                 OR LOWER(cr.awb)           LIKE CONCAT('%', LOWER(:term), '%') )
            ORDER BY o.created_at DESC
            """, nativeQuery = true)
    List<OrderEntity> search(@Param("term") String term, @Param("createdBy") Long createdBy);

    /** All orders for a scope (no search term), most recent first. */
    @Query(value = """
            SELECT o.* FROM orders o
            WHERE (:createdBy IS NULL OR o.created_by = :createdBy)
            ORDER BY o.created_at DESC
            """, nativeQuery = true)
    List<OrderEntity> findAllScoped(@Param("createdBy") Long createdBy);

    /**
     * All orders created by any of the given users, most recent first — the
     * team-lead equivalent of {@link #findAllScoped(Long)}. Callers must pass a
     * non-empty collection (short-circuit to an empty list when a team lead has
     * no assigned salespeople).
     */
    @Query(value = """
            SELECT o.* FROM orders o
            WHERE o.created_by IN (:createdByIds)
            ORDER BY o.created_at DESC
            """, nativeQuery = true)
    List<OrderEntity> findAllScopedIn(@Param("createdByIds") java.util.Collection<Long> createdByIds);

    /**
     * Role-scoped search restricted to a set of creators (team-lead variant of
     * {@link #search(String, Long)}). Callers pass a non-empty set of the team's
     * salesperson ids.
     */
    @Query(value = """
            SELECT DISTINCT o.* FROM orders o
            LEFT JOIN courier_records cr ON cr.order_id = o.id
            WHERE o.created_by IN (:createdByIds)
              AND ( LOWER(o.customer_name)  LIKE CONCAT('%', LOWER(:term), '%')
                 OR o.customer_mobile       LIKE CONCAT('%', :term, '%')
                 OR LOWER(o.order_code)     LIKE CONCAT('%', LOWER(:term), '%')
                 OR CAST(o.id AS CHAR)      LIKE CONCAT('%', :term, '%')
                 OR LOWER(cr.awb)           LIKE CONCAT('%', LOWER(:term), '%') )
            ORDER BY o.created_at DESC
            """, nativeQuery = true)
    List<OrderEntity> searchIn(@Param("term") String term,
                               @Param("createdByIds") java.util.Collection<Long> createdByIds);

    /**
     * A logged-in customer's order history (Phase B): orders linked to their
     * account ({@code customer_user_id}) OR placed as a guest with their mobile
     * number, most recent first. The {@code mobile} may be null (no mobile on the
     * account), in which case only account-linked orders match.
     */
    @Query(value = """
            SELECT o.* FROM orders o
            WHERE o.customer_user_id = :userId
               OR (:mobile IS NOT NULL AND o.customer_mobile = :mobile)
            ORDER BY o.created_at DESC
            """, nativeQuery = true)
    List<OrderEntity> findCustomerHistory(@Param("userId") Long userId, @Param("mobile") String mobile);

    /**
     * A single order in a customer's history by order code — matched only when it
     * belongs to them (account link or mobile match), so a customer can fetch
     * their own order detail but not another customer's.
     */
    @Query(value = """
            SELECT o.* FROM orders o
            WHERE o.order_code = :orderCode
              AND ( o.customer_user_id = :userId
                 OR (:mobile IS NOT NULL AND o.customer_mobile = :mobile) )
            """, nativeQuery = true)
    Optional<OrderEntity> findCustomerOrderByCode(@Param("orderCode") String orderCode,
                                                  @Param("userId") Long userId,
                                                  @Param("mobile") String mobile);

    /**
     * Whether a customer has ever ordered a given product (Phase C: verified
     * purchase). A customer "owns" an order when it is linked to their account
     * ({@code customer_user_id}) OR was placed as a guest with their mobile; an
     * order includes the product when any of its line items references it. Used
     * to flag reviews as verified-purchase (never blocks submission).
     */
    @Query(value = """
            SELECT COUNT(*) FROM orders o
            JOIN line_items li ON li.order_id = o.id
            WHERE li.product_id = :productId
              AND ( o.customer_user_id = :userId
                 OR (:mobile IS NOT NULL AND o.customer_mobile = :mobile) )
            """, nativeQuery = true)
    long countCustomerPurchasesOfProduct(@Param("userId") Long userId,
                                         @Param("mobile") String mobile,
                                         @Param("productId") Long productId);

    /**
     * Per-product current-month sales aggregate for the product-detail "Sales
     * Overview" (product stats endpoint): the sum of the product's line totals
     * and the count of distinct orders containing the product, over orders
     * created within {@code [startInclusive, endExclusive)} and NOT in the
     * excluded (non-revenue) statuses. {@code order_status} is persisted as its
     * enum name, so {@code excludedStatuses} carries the status names to exclude
     * (e.g. {@code REJECTED}, {@code CANCELLED} — matching the P&amp;L revenue
     * definition). {@code SUM} is coalesced to 0 so a product with no qualifying
     * sales still returns a row.
     */
    @Query(value = """
            SELECT COALESCE(SUM(li.line_total), 0) AS revenue,
                   COUNT(DISTINCT o.id)            AS orderCount
            FROM line_items li
            JOIN orders o ON o.id = li.order_id
            WHERE li.product_id = :productId
              AND o.created_at >= :startInclusive
              AND o.created_at <  :endExclusive
              AND o.order_status NOT IN (:excludedStatuses)
            """, nativeQuery = true)
    ProductSalesAggregate productSalesStats(@Param("productId") Long productId,
                                            @Param("startInclusive") java.time.LocalDateTime startInclusive,
                                            @Param("endExclusive") java.time.LocalDateTime endExclusive,
                                            @Param("excludedStatuses") java.util.Collection<String> excludedStatuses);

    /**
     * Projection over {@link #productSalesStats}: {@code revenue} is the summed
     * line total (never null — coalesced to 0) and {@code orderCount} the number
     * of distinct qualifying orders.
     */
    interface ProductSalesAggregate {
        java.math.BigDecimal getRevenue();

        long getOrderCount();
    }

    /**
     * Per-salesperson order aggregate for the Salesperson 360 leaderboard
     * (FEATURE request): one row per {@code created_by} with total/this-month/today
     * order counts, revenue (all + this month, excluding REJECTED/CANCELLED),
     * delivered vs failed delivery counts, and outstanding COD. {@code monthStart}
     * / {@code dayStart} bound the windowed sums.
     */
    @Query(value = """
            SELECT o.created_by AS salespersonId,
                   COUNT(*) AS ordersTotal,
                   SUM(CASE WHEN o.created_at >= :monthStart THEN 1 ELSE 0 END) AS ordersThisMonth,
                   SUM(CASE WHEN o.created_at >= :dayStart THEN 1 ELSE 0 END) AS ordersToday,
                   COALESCE(SUM(CASE WHEN o.order_status NOT IN ('REJECTED','CANCELLED')
                                     THEN o.total_amount ELSE 0 END), 0) AS revenueTotal,
                   COALESCE(SUM(CASE WHEN o.created_at >= :monthStart
                                      AND o.order_status NOT IN ('REJECTED','CANCELLED')
                                     THEN o.total_amount ELSE 0 END), 0) AS revenueThisMonth,
                   SUM(CASE WHEN o.order_status IN ('DELIVERED','COD_COLLECTED','CLOSED')
                            THEN 1 ELSE 0 END) AS deliveredCount,
                   SUM(CASE WHEN o.order_status IN ('CUSTOMER_REJECTED','DELIVERY_FAILED','RTO','REDISPATCH')
                            THEN 1 ELSE 0 END) AS failedCount,
                   COALESCE(SUM(o.customer_outstanding), 0) AS codOutstanding
            FROM orders o
            WHERE o.created_by IS NOT NULL
            GROUP BY o.created_by
            """, nativeQuery = true)
    List<SalespersonOrderAggregate> salespersonOrderStats(
            @Param("monthStart") java.time.LocalDateTime monthStart,
            @Param("dayStart") java.time.LocalDateTime dayStart);

    /** Projection over {@link #salespersonOrderStats} (one row per salesperson). */
    interface SalespersonOrderAggregate {
        Long getSalespersonId();

        long getOrdersTotal();

        long getOrdersThisMonth();

        long getOrdersToday();

        java.math.BigDecimal getRevenueTotal();

        java.math.BigDecimal getRevenueThisMonth();

        long getDeliveredCount();

        long getFailedCount();

        java.math.BigDecimal getCodOutstanding();
    }

    // --- Analytics §6 aggregates (targets / retention / forecasting) --------

    /**
     * Per-salesperson revenue + order count over a window {@code [from, to)},
     * excluding REJECTED/CANCELLED (sales-targets attainment, FEATURE-ROADMAP §6.1).
     */
    @Query(value = """
            SELECT o.created_by AS salespersonId,
                   COUNT(*) AS orderCount,
                   COALESCE(SUM(o.total_amount), 0) AS revenue
            FROM orders o
            WHERE o.created_by IS NOT NULL
              AND o.created_at >= :from AND o.created_at < :to
              AND o.order_status NOT IN ('REJECTED','CANCELLED')
            GROUP BY o.created_by
            """, nativeQuery = true)
    List<SalespersonRevenueRow> salespersonRevenueBetween(
            @Param("from") java.time.LocalDateTime from, @Param("to") java.time.LocalDateTime to);

    /** Projection over {@link #salespersonRevenueBetween}. */
    interface SalespersonRevenueRow {
        Long getSalespersonId();

        long getOrderCount();

        java.math.BigDecimal getRevenue();
    }

    /**
     * (mobile, created_at) for every non-rejected/cancelled order, ordered by
     * customer then time — the raw signal for cohort/retention analysis
     * (FEATURE-ROADMAP §6.3). Lightweight projection (no line items).
     */
    @Query(value = """
            SELECT o.customer_mobile AS mobile, o.created_at AS createdAt
            FROM orders o
            WHERE o.customer_mobile IS NOT NULL AND o.customer_mobile <> ''
              AND o.order_status NOT IN ('REJECTED','CANCELLED')
            ORDER BY o.customer_mobile, o.created_at
            """, nativeQuery = true)
    List<CustomerOrderDateRow> customerOrderDates();

    /** Projection over {@link #customerOrderDates}. */
    interface CustomerOrderDateRow {
        String getMobile();

        java.time.LocalDateTime getCreatedAt();
    }

    /**
     * Per-product units sold + distinct orders over a window {@code [from, to)},
     * excluding REJECTED/CANCELLED — the demand signal for forecasting
     * (FEATURE-ROADMAP §6.5).
     */
    @Query(value = """
            SELECT li.product_id AS productId,
                   MAX(li.product_name) AS productName,
                   COALESCE(SUM(li.quantity), 0) AS units,
                   COUNT(DISTINCT o.id) AS orders
            FROM line_items li
            JOIN orders o ON o.id = li.order_id
            WHERE li.product_id IS NOT NULL
              AND o.created_at >= :from AND o.created_at < :to
              AND o.order_status NOT IN ('REJECTED','CANCELLED')
            GROUP BY li.product_id
            """, nativeQuery = true)
    List<ProductDemandRow> productDemandBetween(
            @Param("from") java.time.LocalDateTime from, @Param("to") java.time.LocalDateTime to);

    /** Projection over {@link #productDemandBetween}. */
    interface ProductDemandRow {
        Long getProductId();

        String getProductName();

        long getUnits();

        long getOrders();
    }

    /**
     * Total customer COD still expected — sum of {@code customer_outstanding} on
     * orders not in a terminal collected/failed/cancelled state (cash forecast,
     * FEATURE-ROADMAP §6.5).
     */
    @Query(value = """
            SELECT COALESCE(SUM(o.customer_outstanding), 0) FROM orders o
            WHERE o.order_status NOT IN
              ('CLOSED','COD_COLLECTED','REJECTED','CANCELLED',
               'DELIVERY_FAILED','CUSTOMER_REJECTED','RTO','REDISPATCH')
            """, nativeQuery = true)
    java.math.BigDecimal sumOutstandingCodActive();

    /**
     * COD collected since a timestamp — sum of {@code cod_amount} on orders that
     * reached {@code COD_COLLECTED} and were last updated on/after {@code since}
     * (recent collection run-rate for the cash forecast, FEATURE-ROADMAP §6.5).
     */
    @Query(value = """
            SELECT COALESCE(SUM(o.cod_amount), 0) FROM orders o
            WHERE o.order_status = 'COD_COLLECTED' AND o.updated_at >= :since
            """, nativeQuery = true)
    java.math.BigDecimal sumCodCollectedSince(@Param("since") java.time.LocalDateTime since);
}
