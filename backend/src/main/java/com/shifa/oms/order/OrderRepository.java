package com.shifa.oms.order;

import com.shifa.oms.statemachine.OrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
     * All orders in any of the given lifecycle statuses, most recent first. Backs
     * the reconciliation prepaid/COD segregation view over fulfilled orders
     * (Req 18.4).
     */
    List<OrderEntity> findByOrderStatusInOrderByCreatedAtDesc(java.util.Collection<OrderStatus> statuses);

    /** Count of prior orders for a customer mobile number (Req 22.2). */
    long countByCustomerMobile(String customerMobile);

    /**
     * All orders created within an inclusive timestamp window, used by the P&L
     * report (Feature C3) to sum revenue. The finance service filters out
     * REJECTED / CANCELLED orders when summing revenue.
     */
    List<OrderEntity> findByCreatedAtBetween(java.time.LocalDateTime from, java.time.LocalDateTime to);

    /**
     * How many times a coupon code has been redeemed by a given customer mobile
     * (Phase D per-customer limit enforcement). The coupon code is stored
     * upper-cased on the order, so callers pass a normalized code.
     */
    long countByCouponCodeAndCustomerMobile(String couponCode, String customerMobile);

    /**
     * Orders for a customer mobile number, most recent first (Req 15.1 agent
     * lookup by mobile). May match several orders for a repeat customer.
     */
    List<OrderEntity> findByCustomerMobileOrderByCreatedAtDesc(String customerMobile);

    /** An order visible to a salesperson only when they created it (Req 5.5). */
    Optional<OrderEntity> findByIdAndCreatedBy(Long id, Long createdBy);

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
}
