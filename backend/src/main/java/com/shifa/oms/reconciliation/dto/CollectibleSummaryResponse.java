package com.shifa.oms.reconciliation.dto;

import java.math.BigDecimal;

/**
 * One-tap "money still to collect" summary (ENHANCEMENT 1.4), shown as a panel
 * after a remittance import so the accountant immediately sees what remains:
 * what the courier still owes us (COD collected but not yet remitted, with aging)
 * versus what customers still owe directly. Read-only; composed from the existing
 * COD aging + receivable/order ledgers.
 *
 * @param codPendingFromCourier    unsettled COD total the courier still has to remit
 * @param codOverSlaCount          how many of those are past the courier payout SLA
 * @param codOverSlaAmount         amount of the over-SLA unsettled COD (the urgent chase figure)
 * @param slaDays                  the courier payout SLA window used for the flag
 * @param customerOutstanding      total still owed by customers on active orders (COD + prepaid remainder)
 * @param pendingClaims            unsettled courier loss claims still to file/settle (count)
 * @param pendingClaimsAmount      total value of those pending claims
 * @param totalCollectible         codPendingFromCourier + customerOutstanding + pendingClaimsAmount
 */
public record CollectibleSummaryResponse(
        BigDecimal codPendingFromCourier,
        long codOverSlaCount,
        BigDecimal codOverSlaAmount,
        int slaDays,
        BigDecimal customerOutstanding,
        long pendingClaims,
        BigDecimal pendingClaimsAmount,
        BigDecimal totalCollectible
) {
}
