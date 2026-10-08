package com.shifa.oms.salesperson.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A lapsed customer for the salesperson's "win-back" call list
 * ({@code GET /api/my-day/win-back?days=N}): a customer with no order in the last
 * {@code N} days, so the salesperson can proactively re-engage them. Ranked by
 * lifetime value (highest first) so the most worthwhile calls come first.
 */
public record WinBackCustomer(
        String mobile,
        String customerName,
        LocalDate lastOrderDate,
        long daysSinceLastOrder,
        long orderCount,
        BigDecimal totalValue) {
}
