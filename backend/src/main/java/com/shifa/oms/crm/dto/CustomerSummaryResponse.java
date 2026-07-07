package com.shifa.oms.crm.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Per-customer summary row ({@code GET /api/admin/customers}, "operations depth"
 * Feature 1). A customer is keyed by mobile and aggregated across the
 * {@code orders} table.
 *
 * @param mobile      the 10-digit customer mobile (the aggregation key)
 * @param name        the customer name from their most recent order
 * @param registered  whether a registered CUSTOMER account exists for this mobile
 * @param orderCount  how many orders the customer has placed
 * @param totalSpent  lifetime value (sum of order {@code total_amount})
 * @param lastOrderAt when the customer last ordered
 * @param firstOrderAt when the customer first ordered
 * @param repeatBuyer convenience flag: {@code orderCount > 1}
 */
public record CustomerSummaryResponse(
        String mobile,
        String name,
        boolean registered,
        long orderCount,
        BigDecimal totalSpent,
        LocalDateTime lastOrderAt,
        LocalDateTime firstOrderAt,
        boolean repeatBuyer
) {
}
