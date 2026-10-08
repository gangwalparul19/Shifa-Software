package com.shifa.oms.crm;

import com.shifa.oms.order.OrderEntity;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Read-only aggregation repository for the customer CRM ("operations depth"
 * Feature 1). A "customer" is identified by {@code customer_mobile}; this repo
 * groups the {@code orders} table by that key to produce per-customer summaries.
 *
 * <p>It is a minimal {@link Repository} over {@link OrderEntity} (no CRUD is
 * exposed) so the CRM module stays self-contained without duplicating the order
 * module's write surface. The native {@code GROUP BY} query supports an optional
 * {@code q} substring over name/mobile and pages/sorts via the caller-supplied
 * {@link Pageable} (sort fields are whitelisted to the SELECT aliases by the
 * controller: {@code totalSpent} / {@code orderCount} / {@code lastOrderAt}).
 */
public interface CustomerRepository extends Repository<OrderEntity, Long> {

    /**
     * Per-customer aggregation grouped by {@code customer_mobile}. The name is
     * taken from the customer's most recent order; {@code totalSpent} is the sum
     * of {@code total_amount} (lifetime value); {@code orderCount} the number of
     * orders; and the first/last order timestamps bound their activity.
     *
     * <p>Returns <em>all</em> matching customer rows unsorted; the service sorts
     * and paginates in memory. This deliberately avoids Spring Data's native-query
     * {@code Pageable} sorting, which qualifies the sort with the table alias
     * ({@code o.totalSpent}) and fails because {@code totalSpent} is a SELECT
     * alias, not a real column. The customer set is small, so in-memory paging is
     * fine.
     *
     * <p>A non-null {@code createdBy} scopes the aggregation to orders created by
     * that user — the salesperson scoping rule (Req 5.5): a salesperson only sees
     * customers derived from their own orders. A {@code null} {@code createdBy}
     * disables scoping (ADMIN / ACCOUNTANT see every customer, Req 5.4).
     *
     * @param q         case-insensitive substring over customer name or mobile (nullable → all)
     * @param createdBy the {@code created_by} constraint, or null for no scoping
     */
    @Query(value = """
            SELECT o.customer_mobile AS mobile,
                   SUBSTRING_INDEX(
                       GROUP_CONCAT(o.customer_name ORDER BY o.created_at DESC SEPARATOR 0x1f),
                       0x1f, 1) AS name,
                   COUNT(*) AS orderCount,
                   COALESCE(SUM(o.total_amount), 0) AS totalSpent,
                   MAX(o.created_at) AS lastOrderAt,
                   MIN(o.created_at) AS firstOrderAt
            FROM orders o
            WHERE (:createdBy IS NULL OR o.created_by = :createdBy)
              AND (:q IS NULL
                   OR LOWER(o.customer_name) LIKE CONCAT('%', LOWER(:q), '%')
                   OR o.customer_mobile LIKE CONCAT('%', :q, '%'))
            GROUP BY o.customer_mobile
            """,
            nativeQuery = true)
    List<CustomerSummaryProjection> aggregateAll(@Param("q") String q, @Param("createdBy") Long createdBy);
}
