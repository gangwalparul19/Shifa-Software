package com.shifa.oms.salesperson.dto;

import java.math.BigDecimal;

/**
 * A salesperson's "My Day" snapshot — the numbers that matter for a productive
 * day, scoped to their own orders ({@code GET /api/my-day}):
 * <ul>
 *   <li>today's orders + revenue,</li>
 *   <li>this month's orders + revenue vs. their monthly target (with % progress),</li>
 *   <li>payments still to chase (orders with a positive balance).</li>
 * </ul>
 * Revenue figures exclude rejected/cancelled orders (matching the P&amp;L /
 * performance definitions). {@code monthTarget} is null when none is set.
 */
public record MyDayResponse(
        long ordersToday,
        BigDecimal revenueToday,
        long monthOrders,
        BigDecimal monthRevenue,
        BigDecimal monthTarget,
        int targetProgressPct,
        long pendingPaymentsCount,
        BigDecimal pendingPaymentsAmount) {
}
