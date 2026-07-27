package com.shifa.oms.performance.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * The team-lead performance dashboard payload ({@code GET /api/team/performance}).
 * Rolls up the orders + leads of the salespeople assigned to a team lead into
 * headline KPIs, a per-salesperson leaderboard, and lead-source conversion so a
 * lead can see how their team is doing and which source converts best.
 *
 * @param memberCount         number of salespeople in the team
 * @param ordersTotal         lifetime orders across the team
 * @param ordersThisMonth     orders created this month across the team
 * @param revenueTotal        lifetime revenue across the team (excl. rejected/cancelled)
 * @param revenueThisMonth    this month's revenue across the team
 * @param delivered           concluded successful deliveries across the team
 * @param failed              concluded failed deliveries across the team
 * @param deliverySuccessRate delivered / (delivered + failed) as a percentage (0–100)
 * @param codOutstanding      COD still outstanding across the team's orders
 * @param leadsTotal          leads captured across the team
 * @param leadsWon            leads won across the team
 * @param leadConversionRate  leadsWon / leadsTotal as a percentage (0–100)
 * @param topPerformerName    the team member with the highest this-month revenue (nullable)
 * @param topSource           the best-converting lead source with ≥1 lead (nullable)
 * @param leaderboard         per-salesperson headline metrics (best month first)
 * @param leadSources         per-source conversion, best rate first
 */
public record TeamPerformanceResponse(
        int memberCount,
        long ordersTotal,
        long ordersThisMonth,
        BigDecimal revenueTotal,
        BigDecimal revenueThisMonth,
        long delivered,
        long failed,
        double deliverySuccessRate,
        BigDecimal codOutstanding,
        long leadsTotal,
        long leadsWon,
        double leadConversionRate,
        String topPerformerName,
        String topSource,
        List<SalespersonPerformanceSummary> leaderboard,
        List<TeamSourceConversion> leadSources
) {
}
