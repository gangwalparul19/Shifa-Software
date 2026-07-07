package com.shifa.oms.crm;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Spring Data projection for the per-customer aggregation over the {@code orders}
 * table ("operations depth" Feature 1). One row per distinct
 * {@code customer_mobile}, produced by the {@code GROUP BY} query in
 * {@link CustomerRepository#aggregate}.
 *
 * <p>The column aliases in the native query match these accessor names so
 * Hibernate can bind the result to the projection.
 */
public interface CustomerSummaryProjection {

    /** The grouping key: the customer's 10-digit mobile. */
    String getMobile();

    /** The customer name from their most recent order. */
    String getName();

    /** How many orders the customer has placed (all sources). */
    long getOrderCount();

    /** Lifetime value: the sum of {@code total_amount} across the customer's orders. */
    BigDecimal getTotalSpent();

    /** When the customer last ordered. */
    LocalDateTime getLastOrderAt();

    /** When the customer first ordered. */
    LocalDateTime getFirstOrderAt();
}
