package com.shifa.oms.dashboard.dto;

import java.math.BigDecimal;
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
     * A salesperson's own leads/orders grouped by status plus the count of their
     * orders awaiting admin approval (Req 3.2).
     */
    public record Salesperson(Map<String, Long> ordersByStatus, long awaitingApproval) {
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
            long handedOverAwaitingDispatch) {
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
