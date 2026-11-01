package com.shifa.oms.performance.dto;

import java.util.List;

/**
 * The full "Salesperson 360" for one salesperson: headline summary + a recent
 * daily activity trend + their latest orders + lead/CRM metrics.
 *
 * @param summary      the headline performance metrics (this month / lifetime)
 * @param trend        per-day order count + revenue for the recent window (oldest→newest)
 * @param recentOrders the salesperson's latest orders (newest first)
 * @param leads        lead/CRM performance
 * @param period       figures for the period the caller selected (this month when none was chosen)
 */
public record SalespersonPerformanceDetail(
        SalespersonPerformanceSummary summary,
        List<SalespersonDailyPoint> trend,
        List<SalespersonOrderRow> recentOrders,
        SalespersonLeadMetrics leads,
        SalespersonPeriodDetail period
) {
    /** Compatibility constructor for callers that predate the period selector. */
    public SalespersonPerformanceDetail(
            SalespersonPerformanceSummary summary,
            List<SalespersonDailyPoint> trend,
            List<SalespersonOrderRow> recentOrders,
            SalespersonLeadMetrics leads) {
        this(summary, trend, recentOrders, leads, null);
    }
}
