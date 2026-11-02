package com.shifa.oms.dashboard.dto;

import com.shifa.oms.performance.dto.SalespersonPerformanceSummary;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Team-wise sales + status overview for the admin dashboard ({@code GET
 * /api/admin/dashboard/teams}): "Team Sameer" vs "Team Zeeshan" side by side,
 * so an admin can see at a glance where each team is heading and jump straight
 * to the leads that need a call.
 *
 * <p>A "team" is a {@code TEAM_LEAD} plus the salespeople assigned to them
 * (mirrors {@code SalespersonScopeResolver#creatorScope}, so these numbers
 * agree with what the team lead sees on their own dashboard). Salespeople with
 * no team lead assigned are rolled into {@link #unassigned}, which is
 * {@code null} when every salesperson is on a team. Revenue excludes REJECTED,
 * PAYMENT_REJECTED and CANCELLED orders (the same rule as every other report).
 *
 * <p>The orders and revenue figures cover the chosen {@link #period} (this month when none is
 * chosen). Delivery success, outstanding money, the lead pipeline and the call-out list describe
 * the situation as it is now and do not change with the period.
 *
 * @param period      the period name (TODAY, YESTERDAY, LAST_7_DAYS, MONTH, LAST_MONTH, OVERALL, CUSTOM)
 * @param periodLabel a human label for it, e.g. "This Month" or "2026-10-01 to 2026-10-10"
 * @param from        first day covered, or {@code null} for "Overall"
 * @param to          last day covered (included), or {@code null} for "Overall"
 */
public record TeamsOverviewResponse(
        LocalDate asOf,
        String period,
        String periodLabel,
        LocalDate from,
        LocalDate to,
        List<TeamOverviewRow> teams,
        TeamOverviewRow unassigned) {

    /** One team's (or the unassigned bucket's) headline sales + lead-status snapshot. */
    public record TeamOverviewRow(
            Long teamLeadId,
            String teamLeadName,
            int memberCount,
            long ordersTotal,
            long ordersInPeriod,
            BigDecimal revenueTotal,
            BigDecimal revenueInPeriod,
            BigDecimal averageOrderValue,
            long delivered,
            long failed,
            Double deliverySuccessRate,
            BigDecimal codOutstanding,
            long leadsTotal,
            long leadsWon,
            long leadsLost,
            Double leadConversionRate,
            Map<String, Long> leadPipeline,
            long dueFollowUps,
            List<TeamCallOut> callOuts,
            List<SalespersonPerformanceSummary> members) {
    }

    /**
     * A lead worth calling right now: due or overdue, sorted most-overdue-first
     * within the team so the admin's most urgent call-outs surface at the top.
     */
    public record TeamCallOut(
            Long leadId,
            String customerName,
            String customerMobile,
            String leadSource,
            String status,
            LocalDate followUpDate,
            long overdueDays,
            String ownerName) {
    }
}
