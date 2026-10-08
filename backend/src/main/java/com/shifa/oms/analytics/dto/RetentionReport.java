package com.shifa.oms.analytics.dto;

import java.util.List;

/**
 * Cohort / retention analytics (FEATURE-ROADMAP §6.3): repeat-purchase behaviour
 * and a monthly cohort-retention grid, computed from order history keyed by
 * customer mobile.
 *
 * @param totalCustomers      distinct customers (by mobile) with a non-cancelled order
 * @param repeatCustomers     customers with more than one order
 * @param repeatRatePct       repeatCustomers / totalCustomers as a percentage
 * @param avgOrdersPerCustomer mean orders per customer
 * @param avgDaysToReorder    mean days between a repeat customer's 1st and 2nd order
 * @param reorderBuckets      distribution of days-to-second-order
 * @param cohorts             monthly cohorts, newest first, with retention % per month offset
 */
public record RetentionReport(
        long totalCustomers,
        long repeatCustomers,
        double repeatRatePct,
        double avgOrdersPerCustomer,
        double avgDaysToReorder,
        List<ReorderBucket> reorderBuckets,
        List<CohortRow> cohorts
) {

    /** A bucket in the days-to-second-order distribution. */
    public record ReorderBucket(String label, long count) {
    }

    /**
     * One acquisition cohort's retention.
     *
     * @param cohortMonth   the first-order month, {@code yyyy-MM}
     * @param cohortSize    customers acquired that month
     * @param retentionPct  % of the cohort active in each subsequent month
     *                      (index 0 = the cohort month itself = 100%)
     */
    public record CohortRow(String cohortMonth, long cohortSize, List<Double> retentionPct) {
    }
}
