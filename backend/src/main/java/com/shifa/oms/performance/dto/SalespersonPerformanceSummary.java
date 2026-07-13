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
        BigDecimal codOutstanding
) {
}
