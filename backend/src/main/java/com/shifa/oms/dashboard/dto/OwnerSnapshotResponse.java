package com.shifa.oms.dashboard.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The owner's one-screen "state of the business" snapshot (ENHANCEMENT 1.1 /
 * 1.2): today's trading plus everything that needs attention right now, computed
 * once server-side and used both by the admin dashboard "owner overview" strip
 * and the daily owner email. Read-only; derived entirely from existing data.
 *
 * @param date                 the business day the "today" figures cover (IST)
 * @param ordersToday          orders punched today
 * @param revenueToday         sum of amount received today
 * @param approvalsWaiting     orders in PENDING_ADMIN_APPROVAL
 * @param paymentsPending      orders awaiting payment verification
 * @param failedDeliveries     orders in CUSTOMER_REJECTED / DELIVERY_FAILED
 * @param rtoCount             orders in RTO
 * @param codToCollect         unsettled COD still to collect (total outstanding)
 * @param codOverSla           unsettled COD past the courier payout SLA (count)
 * @param codOverSlaAmount     amount of the over-SLA unsettled COD
 * @param pendingClaims        unsettled courier loss claims to file/settle
 * @param stuckShipments       orders QuikShipX permanently rejected (need re-routing)
 * @param topSalespersonName   the salesperson with the most revenue today, or null
 * @param topSalespersonRevenue that salesperson's revenue today
 * @param attentionTotal       sum of the actionable items (approvals + payments + failed + claims + stuck + cod-over-sla)
 */
public record OwnerSnapshotResponse(
        LocalDate date,
        long ordersToday,
        BigDecimal revenueToday,
        long approvalsWaiting,
        long paymentsPending,
        long failedDeliveries,
        long rtoCount,
        BigDecimal codToCollect,
        long codOverSla,
        BigDecimal codOverSlaAmount,
        long pendingClaims,
        long stuckShipments,
        String topSalespersonName,
        BigDecimal topSalespersonRevenue,
        long attentionTotal
) {
}
