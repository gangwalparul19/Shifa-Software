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
 */
public record TeamsOverviewResponse(
        LocalDate asOf,
        List<TeamOverviewRow> teams,
        TeamOverviewRow unassigned) {

    /** One team's (or the unassigned bucket's) headline sales + lead-status snapshot. */
    public record TeamOverviewRow(
            Long teamLeadId,
            String teamLeadName,
            int memberCount,
            long ordersTotal,
            long ordersThisMonth,
            BigDecimal revenueTotal,
            BigDecimal revenueThisMonth,
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
