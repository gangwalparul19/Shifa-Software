package com.shifa.oms.performance.dto;

import java.math.BigDecimal;

/**
 * Headline performance metrics for one salesperson (Salesperson 360 leaderboard).
 * Lets an admin compare the team at a glance and spot weak performers.
 *
 * @param id                 salesperson user id
 * @param username           login username
 * @param fullName           display name
 * @param active             whether the account is active
 * @param verificationStatus onboarding verification state (enum name, nullable)
 * @param ordersTotal        lifetime orders created
 * @param ordersThisMonth    orders created this calendar month
 * @param ordersToday        orders created today
 * @param revenueTotal       lifetime revenue (excludes rejected/cancelled)
 * @param revenueThisMonth   this month's revenue (excludes rejected/cancelled)
 * @param deliveredCount     concluded successful deliveries
 * @param failedCount        concluded failed deliveries (RTO/rejected/failed/lost)
 * @param successRate        delivered / (delivered + failed) as a percentage (0–100)
 * @param codOutstanding     COD still outstanding across their orders
 * @param ordersInPeriod     orders in the selected Team Performance window
 * @param revenueInPeriod    revenue in the selected Team Performance window
 * @param averageOrderValue  revenue divided by qualifying orders in that window
 * @param rtoCount           RTO/redispatch outcomes in the selected window
 * @param dueFollowUps       due or overdue non-terminal leads
 */
public record SalespersonPerformanceSummary(
        Long id,
        String username,
        String fullName,
        boolean active,
        String verificationStatus,
        long ordersTotal,
        long ordersThisMonth,
        long ordersToday,
        BigDecimal revenueTotal,
        BigDecimal revenueThisMonth,
        long deliveredCount,
        long failedCount,
        double successRate,
        BigDecimal codOutstanding,
        long ordersInPeriod,
        BigDecimal revenueInPeriod,
        BigDecimal averageOrderValue,
        long rtoCount,
        long dueFollowUps
) {
    /** Backward-compatible constructor for existing detail/leaderboard callers. */
    public SalespersonPerformanceSummary(
            Long id, String username, String fullName, boolean active, String verificationStatus,
            long ordersTotal, long ordersThisMonth, long ordersToday,
            BigDecimal revenueTotal, BigDecimal revenueThisMonth,
            long deliveredCount, long failedCount, double successRate, BigDecimal codOutstanding) {
        this(id, username, fullName, active, verificationStatus,
                ordersTotal, ordersThisMonth, ordersToday, revenueTotal, revenueThisMonth,
                deliveredCount, failedCount, successRate, codOutstanding,
                ordersThisMonth, revenueThisMonth,
                ordersThisMonth == 0 || revenueThisMonth == null
                        ? BigDecimal.ZERO
                        : revenueThisMonth.divide(BigDecimal.valueOf(ordersThisMonth), 2, java.math.RoundingMode.HALF_UP),
                0, 0);
    }
}
