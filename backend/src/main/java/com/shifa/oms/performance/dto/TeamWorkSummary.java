package com.shifa.oms.performance.dto;

/** Actionable, team-scoped work counters for the Team Lead home panel. */
public record TeamWorkSummary(
        long pendingApproval,
        long pendingPaymentVerification,
        long failedDelivery,
        long rto,
        long followUpsDue,
        long inactiveMembers,
        long ordersWithoutRecentActivity
) {
}
