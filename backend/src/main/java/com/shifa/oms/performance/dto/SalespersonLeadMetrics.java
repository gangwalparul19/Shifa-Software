package com.shifa.oms.performance.dto;

/**
 * A salesperson's lead/CRM performance (Salesperson 360).
 *
 * @param total          leads they own
 * @param won            converted (WON) leads
 * @param lost           lost leads
 * @param active         leads still in play (not WON/LOST)
 * @param conversionRate won / total as a percentage (0–100)
 * @param dueFollowUps   follow-ups currently due/overdue
 */
public record SalespersonLeadMetrics(
        long total,
        long won,
        long lost,
        long active,
        double conversionRate,
        long dueFollowUps
) {
}
