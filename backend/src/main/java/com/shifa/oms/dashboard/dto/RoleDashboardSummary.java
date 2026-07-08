package com.shifa.oms.dashboard.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * The role-shaped payload of {@code GET /api/dashboard/summary} (design §6.7,
 * Req 3.1&ndash;3.6). Built server-side from the caller's principal: exactly one
 * of the per-role sections is populated (the one matching {@link #role()}); the
 * rest are {@code null}. Salesperson data is scoped to the caller's own orders
 * via the {@code SalespersonScopeResolver} (Req 3.2, 2.6).
 */
public record RoleDashboardSummary(
        String role,
        Salesperson salesperson,
        Admin admin,
        Packing packing,
        Accountant accountant) {

    /**
     * A salesperson's own orders grouped by status, the count of their orders
     * awaiting admin approval (Req 3.2), plus their lead pipeline-by-stage counts
     * and the number of leads with a due follow-up (Req 6.6, lead-management).
     */
    public record Salesperson(
            Map<String, Long> ordersByStatus,
            long awaitingApproval,
            Map<String, Long> leadPipeline,
            long dueFollowUps) {
    }

    /**
     * The admin operational overview (Req 3.3): the pending-approval count, order
     * counts per active fulfilment stage, exception-state counts, and the two
     * handover/dispatch queue sizes.
     */
    public record Admin(
            long pendingApproval,
            Map<String, Long> perActiveStage,
            Map<String, Long> exceptionStates,
            long packedAwaitingHandover,
            long handedOverAwaitingDispatch,
            Leads leads,
            Insights insights) {
    }

    /**
     * The admin statistical-insights overview (statistical-insights-engine, Req
     * 11.1, 11.2): counts of the latest computed date's non-dismissed insights by
     * severity ({@code INFO}/{@code WARNING}/{@code DANGER}) and the top few
     * headlines (DANGER→WARNING→INFO). Both are empty when no insights exist yet.
     */
    public record Insights(
            Map<String, Long> countsBySeverity,
            List<InsightHeadline> top) {
    }

    /** A single insight headline shown on the admin dashboard tile. */
    public record InsightHeadline(String type, String severity, String title) {
    }

    /**
     * The admin leads/conversion overview (Req 6.6, lead-management): total leads
     * captured, the number won, the overall conversion rate ({@code won / leads},
     * a fraction; 0 when there are no leads), and the current pipeline-by-stage
     * counts.
     */
    public record Leads(
            long totalLeads,
            long won,
            BigDecimal conversionRate,
            Map<String, Long> pipelineByStage) {
    }

    /**
     * The packer's work queues (Req 3.4): approved-awaiting-packing, today's
     * packed count, awaiting-handover, and awaiting-dispatch.
     */
    public record Packing(
            long approvedAwaitingPacking,
            long packedToday,
            long awaitingHandover,
            long awaitingDispatch) {
    }

    /**
     * The accountant's money overview (Req 3.5): COD still pending from couriers,
     * the settled COD amount, and total outstanding receivables (COD + claims).
     */
    public record Accountant(
            BigDecimal codPending,
            BigDecimal settled,
            BigDecimal outstandingReceivables) {
    }
}
