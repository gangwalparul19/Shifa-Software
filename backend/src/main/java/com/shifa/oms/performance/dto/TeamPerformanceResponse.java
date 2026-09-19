package com.shifa.oms.performance.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Team-lead performance payload. The original headline fields remain for
 * compatibility; Phase 1–3 fields add selected-period, actionable-work, and
 * explainable coaching projections without widening server-side scope.
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
        List<TeamSourceConversion> leadSources,
        TeamPeriodSummary period,
        TeamWorkSummary work,
        List<TeamCoachingFlag> coachingFlags,
        SalespersonPerformanceSummary ownPerformance,
        TeamPeriodSummary ownPeriod,
        TeamPeriodSummary combinedPeriod,
        List<TeamOrderRow> ownOrders,
        List<TeamOrderRow> teamOrders
) {
    /** Backward-compatible constructor for existing callers/tests. */
    public TeamPerformanceResponse(
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
            List<TeamSourceConversion> leadSources) {
        this(memberCount, ordersTotal, ordersThisMonth, revenueTotal, revenueThisMonth,
                delivered, failed, deliverySuccessRate, codOutstanding, leadsTotal, leadsWon,
                leadConversionRate, topPerformerName, topSource, leaderboard, leadSources,
                null, null, List.of(), null, null, null, List.of(), List.of());
    }
}
