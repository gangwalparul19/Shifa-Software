package com.shifa.oms.performance.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * One performance owner's figures for the period chosen in the Salespeople drawer (today,
 * yesterday, last 7 days, this/last month, overall or a custom range).
 *
 * @param period            the {@code PerformancePeriod} name that was applied
 * @param label             human label, e.g. "Last 7 Days" or "2026-10-01 to 2026-10-10"
 * @param from              first day covered, or {@code null} for "Overall"
 * @param to                last day covered (inclusive), or {@code null} for "Overall"
 * @param orders            orders placed in the period (every status)
 * @param sales             revenue in the period (excludes rejected, payment-rejected and cancelled)
 * @param averageOrderValue sales divided by the orders that count towards sales
 * @param delivered         orders placed in the period that have since been delivered
 * @param failed            orders placed in the period that have since failed (RTO, rejected, ...)
 * @param successRate       delivered / (delivered + failed) as a percentage (0-100)
 * @param daily             per-day orders and sales, oldest first. Days with no orders are included
 *                          for periods up to 92 days; longer periods list only the days that had orders
 * @param recentOrders      the latest orders placed in the period, newest first (capped)
 * @param ordersListed      how many orders {@code recentOrders} holds (the rest are in the exports)
 */
public record SalespersonPeriodDetail(
        String period,
        String label,
        LocalDate from,
        LocalDate to,
        long orders,
        BigDecimal sales,
        BigDecimal averageOrderValue,
        long delivered,
        long failed,
        double successRate,
        List<SalespersonDailyPoint> daily,
        List<SalespersonOrderRow> recentOrders,
        int ordersListed
) {
}
