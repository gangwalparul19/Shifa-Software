package com.shifa.oms.performance.dto;

import java.math.BigDecimal;

/**
 * A salesperson's target vs achievement for a month (FEATURE-ROADMAP §6.1).
 *
 * @param salespersonId   the salesperson
 * @param salespersonName display name
 * @param active          whether the account is active
 * @param month           the target month, {@code yyyy-MM}
 * @param targetAmount    the set target (null when none set)
 * @param achieved        revenue achieved this month (excl. rejected/cancelled)
 * @param orderCount      qualifying orders this month
 * @param attainmentPct   achieved / target as a percentage (null when no target)
 * @param incentivePct    the incentive rate (null when none)
 * @param incentiveAmount computed incentive payable (0 unless target met and a rate is set)
 * @param targetMet       whether achieved >= target (false when no target)
 */
public record SalesTargetRow(
        Long salespersonId,
        String salespersonName,
        boolean active,
        String month,
        BigDecimal targetAmount,
        BigDecimal achieved,
        long orderCount,
        Double attainmentPct,
        BigDecimal incentivePct,
        BigDecimal incentiveAmount,
        boolean targetMet
) {
}
